package com.jjt.ingsis.snippets.background

import com.jjt.ingsis.snippets.background.redis.RedisEventStream
import com.jjt.ingsis.snippets.metadata.AtomicJobWrite
import com.jjt.ingsis.snippets.metadata.JdbcEventDeliveries
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.metadata.OutboxRecovery
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class, OutboxTestConfiguration::class)
class OutboxDeliveryTest(
    @Autowired private val atomic: AtomicJobWrite,
    @Autowired private val deliveries: JdbcEventDeliveries,
    @Autowired private val recovery: OutboxRecovery,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val redis: StringRedisTemplate,
    @Autowired private val container: OutboxRedis,
    @Autowired private val settings: OutboxSettings,
) {
    private val json = JsonMapper.builder().findAndAddModules().build()

    @BeforeEach
    fun clearDeliveries() {
        jdbc.update("DELETE FROM event_outbox")
        jdbc.update("DELETE FROM background_job_items")
        jdbc.update("DELETE FROM background_jobs")
        redis.delete(settings.stream)
    }

    @Test
    fun `full AOF keeps PostgreSQL work and pending deliveries until the filesystem recovers`() {
        assertBoundedAofFixture()
        val first = recordJob()
        assertThat(publisher().publishBatch()).isEqualTo(1)
        redis.opsForStream<String, String>().createGroup(
            settings.stream,
            org.springframework.data.redis.connection.stream.ReadOffset
                .from("0-0"),
            "disk-test",
        )
        container.execInContainer(
            "redis-cli",
            "XREADGROUP",
            "GROUP",
            "disk-test",
            "worker",
            "COUNT",
            "1",
            "STREAMS",
            settings.stream,
            ">",
        )
        assertThat(redis.opsForStream<String, String>().pending(settings.stream, "disk-test")?.totalPendingMessages)
            .isEqualTo(1)
        val second = recordJob()
        try {
            container.execInContainer(
                "sh",
                "-c",
                "dd if=/dev/zero of=/data/fault-fill bs=1024 count=16384 2>/dev/null || true",
            )
            // Exceed the partially allocated AOF block: a tiny append may still fit on a full filesystem.
            container.execInContainer("redis-cli", "SET", "disk-test-trigger", "x".repeat(16384))
            awaitAofStatus("err")
            assertThat(publisher().publishBatch()).isZero()
            assertThat(unpublished(second.eventId)).isTrue()
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM background_jobs WHERE state = 'PENDING'",
                    Int::class.java,
                ),
            ).isEqualTo(2)
            assertThat(events()).containsExactly(first)
            assertThat(redis.opsForStream<String, String>().pending(settings.stream, "disk-test")?.totalPendingMessages)
                .isEqualTo(1)
        } finally {
            container.execInContainer("rm", "-f", "/data/fault-fill")
            awaitAofStatus("ok")
        }
        makeDue()
        assertThat(publisher().publishBatch()).isEqualTo(1)
        assertThat(events()).containsExactly(first, second)
        assertThat(unpublished(second.eventId)).isFalse()
    }

    private fun assertBoundedAofFixture() {
        assertThat(container.execInContainer("redis-cli", "CONFIG", "GET", "dir").stdout).contains("/data")
        assertThat(container.execInContainer("redis-cli", "CONFIG", "GET", "appendonly").stdout).contains("yes")
        // Refuse fault injection unless /data is the bounded test-only tmpfs.
        assertThat(
            container
                .execInContainer(
                    "sh",
                    "-ec",
                    "test \"\$(stat -f -c %T /data)\" = tmpfs; test \"\$(stat -f -c %b /data)\" -le 2048",
                ).exitCode,
        ).isZero()
    }

    private fun awaitAofStatus(expected: String) {
        val deadline =
            System.nanoTime() +
                java.time.Duration
                    .ofSeconds(10)
                    .toNanos()
        while (System.nanoTime() < deadline) {
            if (container
                    .execInContainer("redis-cli", "INFO", "persistence")
                    .stdout
                    .contains("aof_last_write_status:$expected")
            ) {
                return
            }
            Thread.sleep(
                java.time.Duration
                    .ofMillis(100)
                    .toMillis(),
            )
        }
        error("AOF did not reach expected persistence status")
    }

    @Test
    fun `confirmed event is published and its job remains pending`() {
        val event = recordJob()
        assertThat(publisher().publishBatch()).isEqualTo(1)
        assertThat(events()).containsExactly(event)
        assertThat(unpublished(event.eventId)).isFalse()
        assertThat(
            jdbc.queryForObject("SELECT state FROM background_jobs WHERE id = ?", String::class.java, event.jobId),
        ).isEqualTo("PENDING")
        assertThat(publisher().publishBatch()).isZero()
    }

    @Test
    fun `redis outage preserves a pending event and recovery publishes it`() {
        val event = recordJob()
        container.dockerClient.pauseContainerCmd(container.containerId).exec()
        try {
            assertThat(publisher().publishBatch()).isZero()
            assertThat(unpublished(event.eventId)).isTrue()
            assertThat(deliveries.claim()).isNull()
        } finally {
            container.dockerClient.unpauseContainerCmd(container.containerId).exec()
        }
        makeDue()
        assertThat(publisher().publishBatch()).isEqualTo(1)
        // Paused Redis may execute the timed-out append after resuming: duplicates are valid.
        assertThat(events()).isNotEmpty().allMatch { it == event }
    }

    @Test
    fun `lost redis response redelivers the same event without duplicating its job`() {
        val event = recordJob()
        val real = RedisEventStream(redis, settings)
        val uncertain =
            EventStream {
                real.publish(it)
                throw EventStreamUnavailable(IOException("Lost response"))
            }
        assertThat(OutboxPublisher(deliveries, uncertain, settings).publishBatch()).isZero()
        assertThat(unpublished(event.eventId)).isTrue()
        makeDue()
        assertThat(publisher().publishBatch()).isEqualTo(1)
        assertThat(events()).containsExactly(event, event)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM background_jobs", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `database failure after redis acceptance is recoverable after lease expiry`() {
        val event = recordJob()
        val failing =
            object : EventDeliveryStore by deliveries {
                override fun published(
                    delivery: EventDelivery,
                    recordId: String,
                ): Boolean = error("Database unavailable")
            }
        assertThatThrownBy { OutboxPublisher(failing, RedisEventStream(redis, settings), settings).publishBatch() }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(unpublished(event.eventId)).isTrue()
        assertThat(deliveries.claim()).isNull()
        expireLeases()
        assertThat(publisher().publishBatch()).isEqualTo(1)
        assertThat(events()).containsExactly(event, event)
    }

    @Test
    fun `expired publisher cannot mark or release the replacement reservation`() {
        recordJob()
        val first = checkNotNull(deliveries.claim())
        assertThat(deliveries.claim()).isNull()
        expireLeases()
        val replacement = checkNotNull(deliveries.claim())
        assertThat(replacement.leaseToken).isNotEqualTo(first.leaseToken)
        assertThat(deliveries.published(first, "1-0")).isFalse()
        assertThat(deliveries.retry(first, java.time.Instant.now(), "late")).isFalse()
        assertThat(deliveries.published(replacement, "2-0")).isTrue()
    }

    @Test
    fun `memory rejection leaves publication pending`() {
        val event = recordJob()
        container.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory", "1")
        try {
            assertThat(publisher().publishBatch()).isZero()
            assertThat(unpublished(event.eventId)).isTrue()
            assertThat(events()).isEmpty()
        } finally {
            container.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory", "0")
        }
        makeDue()
        assertThat(publisher().publishBatch()).isEqualTo(1)
    }

    @Test
    fun `redis reconstruction requeues only due unreserved incomplete jobs in bounded batches`() {
        val pending = recordJob()
        val finished = recordJob()
        val leased = recordJob()
        val delayed = recordJob()
        assertThat(publisher().publishBatch()).isEqualTo(4)
        jdbc.update(
            "UPDATE background_jobs SET state = 'COMPLETED', finished_at = CURRENT_TIMESTAMP WHERE id = ?",
            finished.jobId,
        )
        jdbc.update(
            "UPDATE background_jobs SET lease_token = ?, " +
                "lease_until = CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE id = ?",
            UUID.randomUUID(),
            leased.jobId,
        )
        jdbc.update(
            "UPDATE background_jobs SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE id = ?",
            delayed.jobId,
        )
        redis.delete(settings.stream)
        assertThat(recovery.requeueBatch(1)).isEqualTo(1)
        assertThat(recovery.requeueBatch(1)).isZero()
        assertThat(publisher().publishBatch()).isEqualTo(1)
        assertThat(events()).containsExactly(pending)
    }

    private fun recordJob(): DomainEvent {
        val job = newJob()
        return atomic.commit { ConfirmedJobChange(job.event, job) }
    }

    private fun publisher(): OutboxPublisher = OutboxPublisher(deliveries, RedisEventStream(redis, settings), settings)

    private fun events(): List<DomainEvent> =
        redis.opsForStream<String, String>().range(settings.stream, Range.unbounded()).orEmpty().map {
            json.readValue(it.value.getValue("event"), DomainEvent::class.java)
        }

    private fun unpublished(eventId: UUID): Boolean =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT published_at IS NULL FROM event_outbox WHERE event_id = ?",
                Boolean::class.java,
                eventId,
            ),
        )

    private fun expireLeases() {
        jdbc.update(
            "UPDATE event_outbox SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' " +
                "WHERE lease_token IS NOT NULL",
        )
    }

    private fun makeDue() {
        jdbc.update("UPDATE event_outbox SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'")
    }
}
