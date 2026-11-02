package com.jjt.ingsis.snippets.background

import com.jjt.ingsis.snippets.background.redis.RedisEventStream
import com.jjt.ingsis.snippets.background.redis.RedisStreamInbox
import com.jjt.ingsis.snippets.metadata.AtomicJobWrite
import com.jjt.ingsis.snippets.metadata.JdbcEventDeliveries
import com.jjt.ingsis.snippets.metadata.JdbcExecutionCapacity
import com.jjt.ingsis.snippets.metadata.JdbcJobProgress
import com.jjt.ingsis.snippets.metadata.JdbcRejectedDeliveries
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.metadata.OutboxRecovery
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "snippets.capacity.enabled=true", "snippets.worker.batch-size=1",
        "snippets.worker.reclaim-idle=1ms", "snippets.worker.lease=3s", "execution.recovery.delay-ms=3600000",
    ],
)
@Import(MetadataTestConfiguration::class, OutboxTestConfiguration::class)
class JobConsumerIntegrationTest(
    @Autowired private val jobs: JdbcJobProgress,
    @Autowired private val atomic: AtomicJobWrite,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val redis: StringRedisTemplate,
    @Autowired private val settings: WorkerSettings,
    @Autowired private val heartbeat: LeaseHeartbeat,
    @Autowired private val capacity: JdbcExecutionCapacity,
    @Autowired private val rejected: JdbcRejectedDeliveries,
) {
    private val outbox = OutboxSettings()
    private val json = JsonMapper.builder().findAndAddModules().build()

    @BeforeEach
    fun reset() {
        jdbc.update("DELETE FROM execution_permits")
        jdbc.update("DELETE FROM rejected_stream_deliveries")
        jdbc.update("DELETE FROM event_outbox")
        jdbc.update("DELETE FROM background_job_items")
        jdbc.update("DELETE FROM background_jobs")
        redis.delete(outbox.stream)
    }

    @Test
    fun `event clock never delays initial admission and retries use the database clock`() {
        val original = newJob()
        val future = original.copy(event = original.event.copy(occurredAt = Instant.now().plusSeconds(3600)))
        atomic.commit { ConfirmedJobChange(Unit, future) }
        val lease = checkNotNull(jobs.claim(future.event.jobId)?.lease)
        assertThat(jobs.finish(lease, JobStep.Retry("DEPENDENCY_DOWN"))).isFalse()
        val seconds =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT EXTRACT(EPOCH FROM (next_attempt_at - clock_timestamp())) " +
                        "FROM background_jobs WHERE id = ?",
                    Double::class.java,
                    future.event.jobId,
                ),
            )
        assertThat(seconds).isBetween(0.0, 2.0)
    }

    @Test
    fun `group initialization is idempotent and starts from retained history`() {
        val first = job()
        val second = job()
        val stream = RedisEventStream(redis, outbox)
        stream.publish(first)
        stream.publish(second)
        val inbox = inbox("first")
        assertThat(decode(inbox.fresh().single())).isEqualTo(first)
        inbox.initialize()
        assertThat(decode(inbox.fresh().single())).isEqualTo(second)
        assertThat(pending()).isEqualTo(2)
    }

    @Test
    fun `missing handler stays pending without attempts capacity or ACK`() {
        val event = job()
        val inbox = inbox()
        val delivery = publish(event, inbox)
        val consumer = engine(null)
        repeat(3) { consumer.receive(delivery, inbox) }
        val stored = checkNotNull(jobs.find(event.jobId))
        assertThat(stored.state).isEqualTo(JobState.PENDING)
        assertThat(stored.attempts).isZero()
        assertThat(stored.nextAttempt).isAfter(Instant.now())
        assertThat(jobs.claim(event.jobId)).isNull()
        assertThat(capacity.held(event.jobId)).isNull()
        assertThat(pending()).isEqualTo(1)
    }

    @Test
    fun `restart resumes confirmed item progress and only terminal jobs are acknowledged`() {
        val event = job()
        val first = inbox("before-restart")
        val delivery = publish(event, first)
        val handler = completing(3)
        engine(handler).receive(delivery, first)
        assertThat(jobs.progress(event.jobId)?.processed).isEqualTo(1)
        assertThat(pending()).isEqualTo(1)
        val restarted = inbox("after-restart")
        Thread.sleep(10)
        val reclaimed = restarted.recover().single()
        assertThat(reclaimed.id).isEqualTo(delivery.id)
        repeat(2) {
            due()
            engine(handler).receive(reclaimed, restarted)
        }
        assertThat(jobs.progress(event.jobId)?.processed).isEqualTo(3)
        assertThat(jobs.find(event.jobId)?.state).isEqualTo(JobState.COMPLETED)
        assertThat(pending()).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM background_job_items", Int::class.java)).isEqualTo(3)
    }

    @Test
    fun `crash after result commit and before ACK replays terminal without repeating effects`() {
        val event = job()
        val inbox = inbox()
        val delivery = publish(event, inbox)
        val starts = AtomicInteger()
        val handler =
            handler { work ->
                starts.incrementAndGet()
                completing(1).step(work)
            }
        val lostAck =
            object : StreamInbox by inbox {
                override fun acknowledge(delivery: StreamDelivery): Unit =
                    throw RedisConnectionFailureException("ACK unavailable")
            }
        assertThatThrownBy { engine(handler).receive(delivery, lostAck) }
            .isInstanceOf(RedisConnectionFailureException::class.java)
        assertThat(jobs.progress(event.jobId)?.processed).isEqualTo(1)
        assertThat(pending()).isEqualTo(1)
        engine(handler).receive(delivery, inbox)
        assertThat(starts.get()).isEqualTo(1)
        assertThat(pending()).isZero()
    }

    @Test
    fun `result transaction rollback preserves pending item for recovery`() {
        val event = job()
        val inbox = inbox()
        val delivery = publish(event, inbox)
        val broken =
            handler { work ->
                if (!work.job.selectionComplete) select(work, 1)
                val item = work.items().single()
                work.persist<Unit> {
                    work.complete(item, "not-committed")
                    error("Commit interrupted")
                }
                JobStep.Complete
            }
        engine(broken).receive(delivery, inbox)
        assertThat(jobs.progress(event.jobId)?.processed).isZero()
        assertThat(jobs.find(event.jobId)?.state).isEqualTo(JobState.RETRY)
        assertThat(pending()).isEqualTo(1)
        due()
        engine(completing(1)).receive(delivery, inbox)
        assertThat(jobs.progress(event.jobId)?.processed).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT result_reference FROM background_job_items", String::class.java))
            .isNotEqualTo("not-committed")
    }

    @Test
    fun `two consumers have one effective owner and expired owner cannot write`() {
        val event = job()
        val claims =
            Executors.newFixedThreadPool(2).use { pool ->
                pool.invokeAll(List(2) { Callable { jobs.claim(event.jobId) } }).map { it.get() }.filterNotNull()
            }
        assertThat(claims).hasSize(1)
        val old = checkNotNull(claims.single().lease)
        jobs.select(lease = old, items = listOf(item(event, 1)), cursor = "1", complete = true)
        val selected = jobs.items(old).single()
        expire()
        val replacement = checkNotNull(jobs.claim(event.jobId)?.lease)
        assertThat(replacement.token).isNotEqualTo(old.token)
        assertThatThrownBy {
            jobs.completeItem(lease = old, itemId = selected.id, result = "late", state = ItemState.COMPLETED)
        }.isInstanceOf(LostJobLease::class.java)
        assertThat(jobs.renew(old)).isFalse()
        jobs.completeItem(lease = replacement, itemId = selected.id, result = "new-owner", state = ItemState.COMPLETED)
    }

    @Test
    fun `heartbeat keeps ownership during a slow remote operation`() {
        val event = job()
        val inbox = inbox()
        val delivery = publish(event, inbox)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow =
            handler { work ->
                entered.countDown()
                check(release.await(8, TimeUnit.SECONDS))
                completing(1).step(work)
            }
        Executors.newSingleThreadExecutor().use { pool ->
            val running = pool.submit { engine(slow).receive(delivery, inbox) }
            try {
                check(entered.await(3, TimeUnit.SECONDS))
                Thread.sleep(3500)
                assertThat(jobs.claim(event.jobId)).isNull()
            } finally {
                release.countDown()
            }
            running.get(5, TimeUnit.SECONDS)
        }
        assertThat(jobs.find(event.jobId)?.state).isEqualTo(JobState.COMPLETED)
    }

    @Test
    fun `uncertain remote start retains capacity across lease loss and restart`() {
        val event = job()
        val inbox = inbox()
        val delivery = publish(event, inbox)
        val starts = AtomicInteger()
        val remote =
            handler { work ->
                select(work, 1)
                checkNotNull(work.beginRemote(work.items().single()))
                starts.incrementAndGet()
                JobStep.Unknown("START_ACK_MISSING")
            }
        engine(remote).receive(delivery, inbox)
        assertThat(pending()).isZero() // A durable reconciliation obligation now exists in PostgreSQL.
        assertThat(jobs.find(event.jobId)?.state).isEqualTo(JobState.UNKNOWN)
        assertThat(capacity.held(event.jobId)?.state).isEqualTo("UNKNOWN")
        due()
        engine(remote).cycle(inbox("restarted"))
        assertThat(starts.get()).isEqualTo(1)
        assertThat(capacity.held(event.jobId)).isNotNull()
        due()
        val lease = checkNotNull(jobs.claim(event.jobId)?.lease)
        val permit = checkNotNull(capacity.held(event.jobId))
        assertThatThrownBy { capacity.resolve(lease, permit.id, RemoteTerminationEvidence { null }) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(capacity.held(event.jobId)).isNotNull()
        capacity.resolve(lease, permit.id, RemoteTerminationEvidence { "CONFIRMED_PROCESS_STOPPED" })
        assertThat(capacity.held(event.jobId)).isNull()
        jobs.finish(lease, JobStep.Complete)
        assertThat(jobs.find(event.jobId)?.state).isEqualTo(JobState.PARTIAL)
    }

    @Test
    fun `invalid message is durably aparted without storing raw content or blocking valid work`() {
        val inbox = inbox()
        redis.opsForStream<String, String>().add(outbox.stream, mapOf("event" to "private-source-invalid-json"))
        val bad = inbox.fresh().single()
        engine(completing(1)).receive(bad, inbox)
        assertThat(pending()).isZero()
        val row = jdbc.queryForMap("SELECT * FROM rejected_stream_deliveries")
        assertThat(row["reason"]).isEqualTo("INVALID_ENVELOPE")
        assertThat(checkNotNull(row["payload_hash"]).toString()).hasSize(64)
        assertThat(row.values.joinToString()).doesNotContain("private-source")
        val valid = publish(job(), inbox)
        engine(completing(1)).receive(valid, inbox)
        assertThat(pending()).isZero()
    }

    @Test
    fun `failed rejection persistence never acknowledges the message`() {
        val inbox = inbox()
        redis.opsForStream<String, String>().add(outbox.stream, mapOf("event" to "bad"))
        val delivery = inbox.fresh().single()
        val unavailable =
            JobConsumer(
                jobs = jobs,
                rejected =
                    RejectedDeliveries { _, _ ->
                        throw com.jjt.ingsis.snippets.metadata
                            .MetadataPersistenceException(IllegalStateException("DB down"))
                    },
                capacity = capacity,
                heartbeat = heartbeat,
                handlers = emptyList(),
            )
        assertThatThrownBy { unavailable.receive(delivery, inbox) }.isInstanceOf(RuntimeException::class.java)
        assertThat(pending()).isEqualTo(1)
    }

    @Test
    fun `one failed item does not prevent the remainder of the batch completing`() {
        val event = job()
        val inbox = inbox()
        val delivery = publish(event, inbox)
        val partial =
            handler { work ->
                if (!work.job.selectionComplete) select(work, 3)
                work.items().forEach {
                    work.complete(
                        it,
                        "result",
                        if (it.key ==
                            "item-1"
                        ) {
                            ItemState.FAILED
                        } else {
                            ItemState.COMPLETED
                        },
                    )
                }
                JobStep.Complete
            }
        repeat(3) {
            due()
            engine(partial).receive(delivery, inbox)
        }
        val progress = checkNotNull(jobs.progress(event.jobId))
        assertThat(progress.processed).isEqualTo(2)
        assertThat(progress.failed).isEqualTo(1)
        assertThat(progress.status).isEqualTo(JobState.PARTIAL)
        assertThat(pending()).isZero()
    }

    @Test
    fun `redis loss reconstruction is separate from reclaim and resumes database progress`(
        @Autowired deliveries: JdbcEventDeliveries,
        @Autowired recovery: OutboxRecovery,
    ) {
        val event = job()
        val publisher = OutboxPublisher(deliveries, RedisEventStream(redis, outbox), outbox)
        assertThat(publisher.publishBatch()).isEqualTo(1)
        val first = inbox()
        engine(completing(2)).receive(first.fresh().single(), first)
        assertThat(jobs.progress(event.jobId)?.processed).isEqualTo(1)
        redis.delete(outbox.stream)
        due()
        assertThat(recovery.requeueBatch(10)).isEqualTo(1)
        assertThat(publisher.publishBatch()).isEqualTo(1)
        val rebuilt = inbox("rebuilt")
        val delivery = rebuilt.fresh().single()
        assertThat(decode(delivery)).isEqualTo(event)
        engine(completing(2)).receive(delivery, rebuilt)
        assertThat(jobs.progress(event.jobId)?.processed).isEqualTo(2)
        assertThat(jobs.find(event.jobId)?.state).isEqualTo(JobState.COMPLETED)
    }

    @Test
    fun `event inconsistent with its durable job is aparted without modifying that job`() {
        val event = job()
        val inbox = inbox()
        val delivery = publish(event.copy(correlationId = UUID.randomUUID()), inbox)
        engine(completing(1)).receive(delivery, inbox)
        assertThat(jobs.find(event.jobId)?.state).isEqualTo(JobState.PENDING)
        assertThat(jobs.progress(event.jobId)?.processed).isZero()
        assertThat(jdbc.queryForObject("SELECT reason FROM rejected_stream_deliveries", String::class.java))
            .isEqualTo("EVENT_JOB_MISMATCH")
        assertThat(pending()).isZero()
    }

    @Test
    fun `transient root failures have a finite retry budget`() {
        val event = job()
        val inbox = inbox()
        val delivery = publish(event, inbox)
        val unavailable = engine(handler { JobStep.Retry("DEPENDENCY_UNAVAILABLE") })
        repeat(settings.retries.attempts) {
            due()
            unavailable.receive(delivery, inbox)
        }
        assertThat(jobs.find(event.jobId)?.state).isEqualTo(JobState.FAILED)
        assertThat(pending()).isZero()
    }

    @Test
    fun `selectionComplete distinguishes discovered counts from a definitive total`() {
        val event = job()
        val lease = checkNotNull(jobs.claim(event.jobId)?.lease)
        jobs.select(lease = lease, items = listOf(item(event, 1)), cursor = "page-1", complete = false)
        val selected = jobs.items(lease).single()
        jobs.completeItem(lease = lease, itemId = selected.id, result = "result", state = ItemState.COMPLETED)
        assertThat(jobs.finish(lease, JobStep.Complete)).isFalse()
        val progress = checkNotNull(jobs.progress(event.jobId))
        assertThat(progress.processed).isEqualTo(1)
        assertThat(progress.selectionComplete).isFalse()
        assertThat(progress.status).isEqualTo(JobState.PENDING)
        assertThat(jobs.find(event.jobId)?.cursor).isEqualTo("page-1")
    }

    private fun job(): DomainEvent {
        val job = newJob()
        return atomic.commit { ConfirmedJobChange(job.event, job) }
    }

    private fun inbox(name: String = UUID.randomUUID().toString()): RedisStreamInbox =
        RedisStreamInbox(redis = redis, outbox = outbox, settings = settings, consumer = name).also { it.initialize() }

    private fun engine(handler: BackgroundHandler?): JobConsumer =
        JobConsumer(
            jobs = jobs,
            rejected = rejected,
            capacity = capacity,
            heartbeat = heartbeat,
            handlers = listOfNotNull(handler),
        )

    private fun handler(step: (JobWork) -> JobStep): BackgroundHandler =
        object : BackgroundHandler {
            override val eventType = EventType.SnippetUpdated

            override fun step(work: JobWork): JobStep = step.invoke(work)
        }

    private fun completing(count: Int): BackgroundHandler =
        handler { work ->
            if (!work.job.selectionComplete) select(work, count)
            work.items().forEach { work.complete(it, "result-${it.key}") }
            JobStep.Complete
        }

    private fun select(
        work: JobWork,
        count: Int,
    ) = work.select(items = (1..count).map { item(work.job.event, it) }, cursor = count.toString(), complete = true)

    private fun item(
        event: DomainEvent,
        number: Int,
    ): NewJobItem =
        NewJobItem(
            id = UUID.randomUUID(),
            jobId = event.jobId,
            key = "item-$number",
            revisions = event.revisions,
        )

    private fun publish(
        event: DomainEvent,
        inbox: StreamInbox,
    ): StreamDelivery {
        RedisEventStream(redis, outbox).publish(event)
        return inbox.fresh().single()
    }

    private fun decode(delivery: StreamDelivery): DomainEvent =
        json.readValue(delivery.payload, DomainEvent::class.java)

    private fun pending(): Long =
        redis.opsForStream<String, String>().pending(outbox.stream, settings.group)?.totalPendingMessages ?: 0

    private fun due() {
        jdbc.update("UPDATE background_jobs SET next_attempt_at = clock_timestamp() - INTERVAL '1 second'")
    }

    private fun expire() {
        jdbc.update("UPDATE background_jobs SET lease_until = clock_timestamp() - INTERVAL '1 second'")
    }
}
