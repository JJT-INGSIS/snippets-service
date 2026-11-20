package com.jjt.ingsis.snippets.telemetry.background

import com.jjt.ingsis.snippets.background.OutboxRedis
import com.jjt.ingsis.snippets.background.OutboxSettings
import com.jjt.ingsis.snippets.background.OutboxTestConfiguration
import com.jjt.ingsis.snippets.background.WorkerSettings
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

private const val REACTION = "formatting-reprocess"
private const val FIVE_MINUTES = 300L
private const val SIX_MINUTES = 360L
private const val PUBLISHED_EVENTS = 3L

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class, OutboxTestConfiguration::class)
class BackgroundSamplerIntegrationTest(
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val redis: StringRedisTemplate,
    @Autowired private val outbox: OutboxSettings,
    @Autowired private val worker: WorkerSettings,
    @Autowired private val container: OutboxRedis,
) {
    private val settings = BackgroundSamplerSettings(environment = "test")
    private var recorded: List<BackgroundSample> = emptyList()

    private val sampler =
        BackgroundSampler(
            database = DatabaseBacklog(jdbc, settings),
            stream = StreamBacklog(redis = redis, stream = outbox.stream, group = worker.group, settings = settings),
            sink = { samples -> recorded = samples },
            settings = settings,
        )

    @BeforeEach
    fun clearState() {
        jdbc.update("DELETE FROM execution_permits WHERE kind = 'BACKGROUND'")
        jdbc.update("DELETE FROM event_outbox")
        jdbc.update("DELETE FROM background_job_items")
        jdbc.update("DELETE FROM background_jobs")
        redis.delete(outbox.stream)
    }

    private fun rows(aspect: String) = recorded.filter { it.aspect == aspect }.map { it.attributes }

    private fun row(aspect: String) = rows(aspect).single()

    private fun jobTotal() = rows("jobs").single { it["reaction"] == "all" }

    private fun job(
        state: String,
        createdSecondsAgo: Long = 0,
        nextAttemptInSeconds: Long = 0,
        leasedFor: Long? = null,
        finished: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO background_jobs (id, event_id, reaction, scope, revisions, state, next_attempt_at,
                lease_token, lease_until, created_at, updated_at, finished_at)
            VALUES (?, ?, ?, '{}'::jsonb, '{}'::jsonb, ?, now() + make_interval(secs => ?),
                ?, CASE WHEN ? THEN now() + make_interval(secs => ?) END,
                now() - make_interval(secs => ?), now() - make_interval(secs => ?), CASE WHEN ? THEN now() END)
            """.trimIndent(),
            id,
            UUID.randomUUID(),
            REACTION,
            state,
            nextAttemptInSeconds,
            leasedFor?.let { UUID.randomUUID() },
            leasedFor != null,
            leasedFor ?: 0,
            createdSecondsAgo,
            createdSecondsAgo,
            finished,
        )
        return id
    }

    private fun item(
        jobId: UUID,
        updatedSecondsAgo: Long,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO background_job_items (id, job_id, item_key, revisions, state, next_attempt_at, created_at,
                updated_at)
            VALUES (?, ?, ?, '{}'::jsonb, 'RUNNING', now(), now() - make_interval(secs => ?),
                now() - make_interval(secs => ?))
            """.trimIndent(),
            id,
            jobId,
            id.toString(),
            updatedSecondsAgo,
            updatedSecondsAgo,
        )
        return id
    }

    private fun publishEvents(count: Long) =
        (1..count).forEach { redis.opsForStream<String, String>().add(outbox.stream, mapOf("event" to "{}")) }

    @Test
    fun `an idle system reports explicit zeros and an absent stream as unknown`() {
        sampler.sample()

        assertThat(row("outbox")).containsEntry("pending", 0L).containsEntry("oldestPendingSeconds", 0L)
        assertThat(jobTotal()).containsEntry("count", 0L).containsEntry("oldestEligibleSeconds", 0L)
        assertThat(row("capacity")).containsEntry("heldBackground", 0L)
        assertThat(row("stream"))
            .containsEntry("streamExists", false)
            .containsEntry("lagKnown", false)
            .doesNotContainKey("lag")
        assertThat(recorded).allSatisfy { assertThat(it.attributes).containsEntry("samplerError", false) }
        assertThat(recorded).allSatisfy { assertThat(it.attributes).containsEntry("environment", "test") }
    }

    @Test
    fun `a delayed publication shows pending events, their age and the failing reason`() {
        val jobId = job("PENDING", createdSecondsAgo = FIVE_MINUTES)
        jdbc.update(
            """
            INSERT INTO event_outbox (event_id, job_id, envelope, created_at, attempts, next_attempt_at, last_error)
            SELECT event_id, id, '{}'::jsonb, created_at, 2, now(), 'REDIS_WRITE_UNAVAILABLE'
            FROM background_jobs WHERE id = ?
            """.trimIndent(),
            jobId,
        )

        sampler.sample()

        assertThat(row("outbox")).containsEntry("pending", 1L).containsEntry("retrying", 1L)
        assertThat(row("outbox")["oldestPendingSeconds"] as Long).isGreaterThanOrEqualTo(FIVE_MINUTES)
        assertThat(row("outbox")["failingReasons"]).isEqualTo("REDIS_WRITE_UNAVAILABLE")
    }

    @Test
    fun `a stopped consumer shows lag and an aging eligible job while nothing is unacknowledged`() {
        publishEvents(PUBLISHED_EVENTS)
        redis.opsForStream<String, String>().createGroup(outbox.stream, ReadOffset.from("0-0"), worker.group)
        job("PENDING", createdSecondsAgo = SIX_MINUTES)

        sampler.sample()

        assertThat(row("stream"))
            .containsEntry("streamExists", true)
            .containsEntry("groupExists", true)
            .containsEntry("lagKnown", true)
            .containsEntry("lag", PUBLISHED_EVENTS)
            .containsEntry("pending", 0L)
        assertThat(jobTotal()["oldestEligibleSeconds"] as Long).isGreaterThanOrEqualTo(SIX_MINUTES)
        assertThat(row("outbox")).containsEntry("pending", 0L)
    }

    @Test
    fun `a delivery without ACK shows in the pending entries list`() {
        publishEvents(1)
        redis.opsForStream<String, String>().createGroup(outbox.stream, ReadOffset.from("0-0"), worker.group)
        redis.opsForStream<String, String>().read(
            Consumer.from(worker.group, "stalled-worker"),
            StreamReadOptions.empty().count(1),
            StreamOffset.create(outbox.stream, ReadOffset.lastConsumed()),
        )

        sampler.sample()

        assertThat(row("stream"))
            .containsEntry("pending", 1L)
            .containsEntry("lag", 0L)
            .containsEntry("consumers", 1L)
    }

    @Test
    fun `a leased job without durable progress is told apart from an eligible one`() {
        val jobId = job("RUNNING", createdSecondsAgo = FIVE_MINUTES, leasedFor = FIVE_MINUTES)
        item(jobId, updatedSecondsAgo = FIVE_MINUTES)

        sampler.sample()

        val running = rows("jobs").single { it["state"] == "RUNNING" }
        assertThat(running["stalestProgressSeconds"] as Long).isGreaterThanOrEqualTo(FIVE_MINUTES)
        assertThat(running).containsEntry("oldestEligibleSeconds", 0L)
    }

    @Test
    fun `backoff, recent partial results and retained capacity are reported`() {
        job("RETRY", nextAttemptInSeconds = FIVE_MINUTES)
        job("PARTIAL", finished = true)
        val held = job("RUNNING", leasedFor = FIVE_MINUTES)
        val heldItem = item(held, updatedSecondsAgo = 0)
        jdbc.update(
            "INSERT INTO execution_permits (id, kind, job_id, item_id, state) " +
                "VALUES (?, 'BACKGROUND', ?, ?, 'UNKNOWN')",
            UUID.randomUUID(),
            held,
            heldItem,
        )

        sampler.sample()

        assertThat(rows("jobs").single { it["state"] == "RETRY" }).containsEntry("backoff", 1L)
        assertThat(rows("jobs-finished").single()).containsEntry("state", "PARTIAL").containsEntry("count", 1L)
        assertThat(row("capacity")).containsEntry("heldBackground", 1L).containsEntry("unknownPermits", 1L)
    }

    @Test
    fun `a failing aspect is marked without zeros while the others are still reported`() {
        redis.opsForValue().set(outbox.stream, "not a stream")

        sampler.sample()

        assertThat(row("stream")).containsEntry("samplerError", true).doesNotContainKeys("pending", "lagKnown")
        assertThat(row("outbox")).containsEntry("samplerError", false).containsEntry("pending", 0L)
        assertThat(container.isRunning).isTrue()
    }
}
