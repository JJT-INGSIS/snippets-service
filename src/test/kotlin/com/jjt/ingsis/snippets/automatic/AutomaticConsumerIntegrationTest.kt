package com.jjt.ingsis.snippets.automatic

import com.jjt.ingsis.snippets.background.DomainEvent
import com.jjt.ingsis.snippets.background.JobConsumer
import com.jjt.ingsis.snippets.background.OutboxSettings
import com.jjt.ingsis.snippets.background.OutboxTestConfiguration
import com.jjt.ingsis.snippets.background.WorkerSettings
import com.jjt.ingsis.snippets.background.redis.RedisEventStream
import com.jjt.ingsis.snippets.background.redis.RedisStreamInbox
import com.jjt.ingsis.snippets.creation.draft
import com.jjt.ingsis.snippets.execution.AuditedRun
import com.jjt.ingsis.snippets.execution.ControlledExecutor
import com.jjt.ingsis.snippets.execution.ExecutionAudit
import com.jjt.ingsis.snippets.execution.ExecutionScript
import com.jjt.ingsis.snippets.execution.snapshot
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.language.execution.ExecutionState
import com.jjt.ingsis.snippets.language.execution.LanguageExecutor
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StoreResult
import com.jjt.ingsis.snippets.testcase.TestCaseDefinition
import com.jjt.ingsis.snippets.testcase.TestCaseStore
import com.jjt.ingsis.snippets.update.UpdateCandidate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "snippets.capacity.enabled=true", "snippets.worker.batch-size=1", "snippets.worker.lease=10s",
        "execution.retention.delay-ms=3600000", "execution.recovery.delay-ms=3600000",
    ],
)
@Import(MetadataTestConfiguration::class, OutboxTestConfiguration::class)
@MockitoBean(types = [Language::class])
class AutomaticConsumerIntegrationTest
    @Autowired
    constructor(
        private val jdbc: JdbcTemplate,
        private val commits: SnippetUpdateCommit,
        private val storage: SnippetContentStorage,
        private val tests: TestCaseStore,
        private val consumer: JobConsumer,
        private val language: Language,
        private val redis: StringRedisTemplate,
        private val audit: ExecutionAudit,
    ) {
        private val controlled = ControlledExecutor()
        private val processor = ProcessorRevision("sha256-v1:" + "1".repeat(64))
        private val executor =
            object : LanguageExecutor by controlled {
                override fun processorRevision(): ExecutionResult<ProcessorRevision> =
                    ExecutionResult.Success(processor)
            }
        private val outbox = OutboxSettings(stream = "automatic-test-it")
        private val settings =
            WorkerSettings(batchSize = 1, lease = Duration.ofSeconds(10), reclaimIdle = Duration.ofMillis(1))
        private val json = JsonMapper.builder().findAndAddModules().build()

        @BeforeEach
        fun reset() {
            controlled.processor.set(processor)
            `when`(language.executorFor("printscript")).thenReturn(ExecutionResult.Success(executor))
            jdbc.update("DELETE FROM snippet_execution_audit WHERE background_permit_id IS NOT NULL")
            jdbc.update("DELETE FROM execution_permits")
            jdbc.update("DELETE FROM snippet_execution_audit")
            jdbc.update("DELETE FROM snippet_update_snapshots")
            jdbc.update("DELETE FROM event_outbox")
            jdbc.update("DELETE FROM background_job_items")
            jdbc.update("DELETE FROM background_jobs")
            redis.delete(outbox.stream)
        }

        @Test
        fun `real Redis re-deliveries execute each snapshot test once and preserve exact comparison`() {
            val id = snippet()
            tests.create(id, TestCaseDefinition(listOf("", "ñ", "ñ"), listOf("", "ñ", "ñ")), 10)
            tests.create(id, TestCaseDefinition(emptyList(), listOf("ñ", "")), 10)
            val job = update(id, "println(1);")
            finish(job)
            repeat(3) { tick(job) }
            assertThat(controlled.requests).hasSize(2)
            assertThat(controlled.requests.first().inputs).containsExactly("", "ñ", "ñ")
            assertThat(comparisons(job)).containsExactlyInAnyOrder("PASSED", "FAILED")
            assertThat(state(job)).isEqualTo("COMPLETED")
            assertThat(held(job)).isZero()
            val runIds =
                jdbc.query(
                    "SELECT a.id FROM snippet_execution_audit a JOIN execution_permits p " +
                        "ON p.id = a.background_permit_id " +
                        "WHERE p.job_id = ?",
                    { row, _ -> row.getObject(1, UUID::class.java) },
                    job,
                )
            assertThat(runIds).hasSize(2).allSatisfy { assertThat(audit.find(it)).isNull() }
        }

        @Test
        fun `an empty snapshot completes without contacting the interpreter`() {
            val job = update(snippet(), "println(1);")
            finish(job)
            assertThat(state(job)).isEqualTo("COMPLETED")
            assertThat(controlled.requests).isEmpty()
            assertThat(held(job)).isZero()
        }

        @Test
        fun `two updates run their own code and definitions without reading the latest blob as history`() {
            val id = snippet()
            tests.create(id, TestCaseDefinition(emptyList(), listOf("", "ñ", "ñ")), 10)
            val first = update(id, "println(1);")
            tests.create(id, TestCaseDefinition(listOf("second"), listOf("different")), 10)
            val second = update(id, "println(2);")
            finish(first)
            finish(second)
            assertThat(controlled.requests.map { it.code }).containsExactly("println(1);", "println(2);", "println(2);")
            assertThat(comparisons(first)).containsExactly("PASSED")
            assertThat(comparisons(second)).containsExactlyInAnyOrder("PASSED", "FAILED")
        }

        @Test
        fun `an uncertain start is never repeated by re-delivery or a new Redis consumer`() {
            val id = snippet()
            tests.create(id, TestCaseDefinition(emptyList(), emptyList()), 10)
            controlled.startFailure.set(ExecutionResult.Failed("lost-acknowledgment", uncertain = true))
            val job = update(id, "println(1);")
            repeat(12) { tick(job, "restarted-$it") }
            assertThat(controlled.requests).hasSize(1)
            assertThat(state(job)).isEqualTo("UNKNOWN")
            assertThat(held(job)).isEqualTo(1)
            assertThat(
                jdbc.queryForObject(
                    "SELECT released_at IS NULL FROM snippet_update_snapshots WHERE job_id = ?",
                    Boolean::class.java,
                    job,
                ),
            ).isTrue()
            assertThat(comparisons(job)).containsExactlyElementsOf(listOf<String?>(null))
        }

        @Test
        fun `a known session is recovered from retained events without another start`() {
            val id = snippet()
            tests.create(id, TestCaseDefinition(emptyList(), listOf("", "ñ", "ñ")), 10)
            controlled.openFailure.set("stream-temporarily-unavailable")
            val job = update(id, "println(1);")
            repeat(3) { tick(job) }
            assertThat(state(job)).isEqualTo("UNKNOWN")
            controlled.openFailure.set(null)
            finish(job, "new-consumer")
            assertThat(controlled.requests).hasSize(1)
            assertThat(comparisons(job)).containsExactly("PASSED")
            assertThat(held(job)).isZero()
        }

        @Test
        fun `expired remote history is unknown rather than a failed assertion and releases proven absence`() {
            val id = snippet()
            tests.create(id, TestCaseDefinition(emptyList(), emptyList()), 10)
            controlled.openFailure.set("stream-temporarily-unavailable")
            val job = update(id, "println(1);")
            repeat(3) { tick(job) }
            controlled.describeFailure.set(ExecutionResult.Rejected("execution-not-found"))
            finish(job)
            assertThat(controlled.requests).hasSize(1)
            assertThat(state(job)).isEqualTo("PARTIAL")
            assertThat(held(job)).isZero()
            assertThat(comparisons(job)).containsExactlyElementsOf(listOf<String?>(null))
            assertThat(
                jdbc.queryForObject(
                    "SELECT a.failure FROM snippet_execution_audit a JOIN execution_permits p " +
                        "ON p.id = a.background_permit_id WHERE p.job_id = ?",
                    String::class.java,
                    job,
                ),
            ).isEqualTo("RESULT_UNKNOWN_SESSION_EXPIRED")
        }

        @Test
        fun `program errors are distinct from assertion failures`() {
            val id = snippet()
            tests.create(id, TestCaseDefinition(emptyList(), emptyList()), 10)
            controlled.script.set(ExecutionScript(outcome = ExecutionState.PROGRAM_ERROR))
            val job = update(id, "println(1);")
            finish(job)
            assertThat(comparisons(job)).containsExactlyElementsOf(listOf<String?>(null))
            assertThat(
                jdbc.queryForObject(
                    "SELECT a.result -> 'terminal' ->> 'outcome' FROM snippet_execution_audit a " +
                        "JOIN execution_permits p " +
                        "ON p.id = a.background_permit_id WHERE p.job_id = ?",
                    String::class.java,
                    job,
                ),
            ).isEqualTo("PROGRAM_ERROR")
        }

        @Test
        fun `capacity waits do not exhaust technical retries and manual work retains its reserved budget`() {
            val id = snippet()
            tests.create(id, TestCaseDefinition(emptyList(), listOf("", "ñ", "ñ")), 10)
            val testId = tests.list(id).single().id
            val manual =
                (1..3).map { index ->
                    AuditedRun(
                        id = UUID.randomUUID(),
                        actorId = "manual-$index",
                        snapshot = snapshot(snippetId = id, testId = testId),
                        startedAt = Instant.now(),
                    ).also { assertThat(audit.reserve(it)).isTrue() }
                }
            val job = update(id, "println(1);")
            repeat(12) { tick(job) }
            assertThat(controlled.requests).isEmpty()
            assertThat(state(job)).isEqualTo("PENDING")
            assertThat(
                jdbc.queryForObject(
                    "SELECT attempts FROM background_job_items WHERE job_id = ?",
                    Int::class.java,
                    job,
                ),
            ).isZero()
            manual.forEach { audit.reject(it.id, "rejected-before-remote-start") }
            finish(job)
            assertThat(controlled.requests).hasSize(1)
        }

        private fun snippet(): UUID {
            val id = UUID.randomUUID()
            val reference = (storage.store("println(0);") as StoreResult.Stored).reference
            jdbc.update(
                "INSERT INTO snippets (id, name, language, version, state, creation_key, creation_actor_id, " +
                    "creation_fingerprint, content_reference) VALUES (?, 'Automatic', 'printscript', '1.0', " +
                    "'CONFIRMED', ?, 'dev-juan', 'fixture', ?)",
                id,
                UUID.randomUUID(),
                reference.value,
            )
            return id
        }

        private fun update(
            id: UUID,
            code: String,
        ): UUID {
            val reference = (storage.store(code) as StoreResult.Stored).reference
            commits.commit(UpdateCandidate(id, draft().copy(code = code), "dev-juan"), reference)
            return checkNotNull(
                jdbc.queryForObject(
                    "SELECT job_id FROM snippet_update_snapshots WHERE snippet_id = ? ORDER BY created_at DESC LIMIT 1",
                    UUID::class.java,
                    id,
                ),
            )
        }

        private fun tick(
            jobId: UUID,
            member: String = "original",
        ) {
            jdbc.update("UPDATE background_jobs SET next_attempt_at = clock_timestamp() WHERE id = ?", jobId)
            jdbc.update("UPDATE background_job_items SET next_attempt_at = clock_timestamp() WHERE job_id = ?", jobId)
            val envelope =
                checkNotNull(
                    jdbc.queryForObject(
                        "SELECT envelope::text FROM event_outbox WHERE job_id = ?",
                        String::class.java,
                        jobId,
                    ),
                )
            val event = json.readValue(envelope, DomainEvent::class.java)
            RedisEventStream(redis, outbox).publish(event)
            consumer.cycle(RedisStreamInbox(redis = redis, outbox = outbox, settings = settings, consumer = member))
        }

        private fun finish(
            jobId: UUID,
            member: String = "original",
        ) {
            repeat(15) { if (state(jobId) !in setOf("COMPLETED", "PARTIAL", "FAILED")) tick(jobId, member) }
            assertThat(state(jobId)).isIn("COMPLETED", "PARTIAL", "FAILED")
        }

        private fun state(jobId: UUID): String =
            checkNotNull(
                jdbc.queryForObject("SELECT state FROM background_jobs WHERE id = ?", String::class.java, jobId),
            )

        private fun held(jobId: UUID): Int =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM execution_permits WHERE job_id = ? AND state <> 'RELEASED'",
                    Int::class.java,
                    jobId,
                ),
            )

        private fun comparisons(jobId: UUID): List<String?> =
            jdbc.query(
                "SELECT a.result ->> 'comparison' FROM snippet_execution_audit a JOIN execution_permits p " +
                    "ON p.id = a.background_permit_id WHERE p.job_id = ?",
                { row, _ -> row.getString(1) },
                jobId,
            )
    }
