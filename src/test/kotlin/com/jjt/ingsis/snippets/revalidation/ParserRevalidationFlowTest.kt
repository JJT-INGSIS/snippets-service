package com.jjt.ingsis.snippets.revalidation

import com.jjt.ingsis.snippets.background.EventType
import com.jjt.ingsis.snippets.background.JobConsumer
import com.jjt.ingsis.snippets.background.JobLease
import com.jjt.ingsis.snippets.background.JobState
import com.jjt.ingsis.snippets.background.NewBackgroundJob
import com.jjt.ingsis.snippets.background.NewJobItem
import com.jjt.ingsis.snippets.background.OutboxPublisher
import com.jjt.ingsis.snippets.background.OutboxSettings
import com.jjt.ingsis.snippets.background.OutboxTestConfiguration
import com.jjt.ingsis.snippets.background.WorkItem
import com.jjt.ingsis.snippets.background.WorkerSettings
import com.jjt.ingsis.snippets.background.redis.RedisEventStream
import com.jjt.ingsis.snippets.background.redis.RedisStreamInbox
import com.jjt.ingsis.snippets.http.ScriptedHttpService
import com.jjt.ingsis.snippets.http.StubRequest
import com.jjt.ingsis.snippets.http.StubResponse
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.metadata.JdbcEventDeliveries
import com.jjt.ingsis.snippets.metadata.JdbcExecutionCapacity
import com.jjt.ingsis.snippets.metadata.JdbcJobProgress
import com.jjt.ingsis.snippets.metadata.JdbcParserActivations
import com.jjt.ingsis.snippets.metadata.JdbcParserValidationAdmission
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.metadata.RetainedContentCleanup
import com.jjt.ingsis.snippets.reformatting.SnippetSeeds
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.time.Duration
import java.util.UUID

private val parserRevision = ProcessorRevision("sha256-v1:" + "a".repeat(64))
private val parserHeader = mapOf("Processor-Revision" to parserRevision.value)
private val parserJson = JsonMapper.builder().build()

private fun parserAnswer(request: StubRequest): StubResponse =
    when (request.path) {
        "/processor" -> {
            StubResponse(
                200,
                """{"artifact":"sha256:artifact","capabilities":{"validate":"${parserRevision.value}"}}""",
            )
        }

        "/validate" -> {
            val bad =
                parserJson
                    .readTree(request.body)
                    .get("code")
                    .asString()
                    .contains("bad")
            StubResponse(
                200,
                if (bad) {
                    """{"valid":false,"diagnostics":[{"rule":"parse","message":"Invalid code","line":1,"column":2}]}"""
                } else {
                    """{"valid":true,"diagnostics":[]}"""
                },
                headers = parserHeader,
            )
        }

        else -> {
            StubResponse(404, "{}")
        }
    }

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "snippets.capacity.enabled=true", "snippets.worker.item-batch-size=2", "snippets.worker.lease=5s",
        "language.printscript.connect-timeout=2s", "language.printscript.read-timeout=2s",
    ],
)
@Import(MetadataTestConfiguration::class, OutboxTestConfiguration::class, SnippetSeeds::class)
class ParserRevalidationFlowTest(
    @Autowired private val activate: ActivateParser,
    @Autowired private val activations: JdbcParserActivations,
    @Autowired private val admission: JdbcParserValidationAdmission,
    @Autowired private val jobs: JdbcJobProgress,
    @Autowired private val capacity: JdbcExecutionCapacity,
    @Autowired private val consumer: JobConsumer,
    @Autowired private val deliveries: JdbcEventDeliveries,
    @Autowired context: ApplicationContext,
) {
    private val seeds = context.getBean(SnippetSeeds::class.java)
    private val storage = context.getBean(SnippetContentStorage::class.java)
    private val cleanup = context.getBean(RetainedContentCleanup::class.java)
    private val jdbc = context.getBean(JdbcTemplate::class.java)
    private val redis = context.getBean(StringRedisTemplate::class.java)
    private val administrator = ParserAdministrator("os:root:uid=0")
    private val scope = ParserScope("printscript", "1.1")
    private val outbox = OutboxSettings()
    private val inboxSettings = WorkerSettings(reclaimIdle = Duration.ofMillis(1), itemBatchSize = 2)

    @BeforeEach
    fun reset() {
        provider.reset()
        redis.delete(outbox.stream)
        jdbc.update("DELETE FROM parser_analysis_admission")
    }

    @Test
    fun `activation atomically records reference audit job and outbox and replay never reactivates an old reference`() {
        val request = request()
        val first = confirm(request)
        assertThat(activations.active(scope)).isEqualTo(first)
        assertThat(
            jdbc.queryForObject(
                "SELECT operator_identity FROM parser_activations WHERE id = ?",
                String::class.java,
                first.id,
            ),
        ).isEqualTo(administrator.identity)
        assertThat(jobs.find(first.jobId)?.event?.eventType).isEqualTo(EventType.ParserVersionActivated)
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM event_outbox WHERE job_id = ?", Int::class.java, first.jobId),
        ).isEqualTo(1)
        val second = confirm(request())
        assertThat(activate.activate(request, administrator)).isEqualTo(ParserActivationResult.Confirmed(first, true))
        assertThat(activations.active(scope)).isEqualTo(second)
        assertThat(activate.activate(request.copy(reason = "different intent"), administrator))
            .isEqualTo(ParserActivationResult.RequestConflict)
    }

    @Test
    fun `unavailable or unverifiable processor and unsupported scope never activate`() {
        provider.answerWith { StubResponse(200, """{"capabilities":{"validate":null}}""") }
        val request = request()
        assertThat(activate.activate(request, administrator)).isEqualTo(ParserActivationResult.ProcessorUnavailable)
        assertThat(activations.replay(request)).isNull()
        assertThat(activate.activate(request.copy(scope = ParserScope("unknown", "1.1")), administrator))
            .isEqualTo(ParserActivationResult.UnsupportedScope)
    }

    @Test
    fun `outbox collision rolls back activation and retains the previous reference`() {
        val first = confirm(request())
        val event = checkNotNull(jobs.find(first.jobId)).event
        val next = request()
        assertThatThrownBy {
            activations.confirm(next, administrator.identity) { id, time ->
                NewBackgroundJob(
                    event.copy(jobId = UUID.randomUUID(), correlationId = id, occurredAt = time),
                    "parser-revalidation",
                )
            }
        }.isInstanceOf(MetadataPersistenceException::class.java)
        assertThat(activations.active(scope)).isEqualTo(first)
        assertThat(activations.replay(next)).isNull()
    }

    @Test
    fun `real Redis flow pages all owners retains invalid diagnostics and ACK replay changes no results`() {
        val valid = seeds.confirmed("println(1);", "1.1", "owner-a")
        val invalid = seeds.confirmed("bad", "1.1", "owner-b")
        val excluded = seeds.confirmed("println(2);", "1.0", "owner-a")
        val references = listOf(valid, invalid, excluded).associateWith(seeds::reference)
        val activation = confirm(request())
        pump(activation.jobId)
        assertThat(result(valid, "valid")).isEqualTo(true)
        assertThat(result(invalid, "valid")).isEqualTo(false)
        assertThat(result(invalid, "diagnostics")?.toString()).contains("Invalid code")
        assertThat(result(excluded, "activation_id")).isNull()
        assertThat(jobs.progress(activation.jobId)?.selectionComplete).isTrue()
        assertThat(jobs.progress(activation.jobId)?.failed).isZero()
        references.forEach { (id, reference) -> assertThat(seeds.reference(id)).isEqualTo(reference) }
        val before = result(valid, "result_id")
        jdbc.update(
            "UPDATE event_outbox SET published_at = NULL, redis_record_id = NULL WHERE job_id = ?",
            activation.jobId,
        )
        publish()
        consumer.cycle(inbox("replayed"))
        assertThat(result(valid, "result_id")).isEqualTo(before)
    }

    @Test
    fun `code modified during validation cannot publish obsolete diagnostics`() {
        val id = seeds.confirmed("change-me", "1.1", "owner-c")
        provider.answerWith { request ->
            if (request.path == "/validate" && request.body.contains("change-me")) seeds.edit(id, "println(9);")
            parserAnswer(request)
        }
        val activation = confirm(request())
        pump(activation.jobId)
        assertThat(result(id, "result_id")).isNull()
        assertThat(seeds.code(checkNotNull(seeds.reference(id)))).isEqualTo("println(9);")
        assertThat(
            jdbc.queryForObject(
                "SELECT state FROM background_job_items WHERE job_id = ? AND item_key = ?",
                String::class.java,
                activation.jobId,
                id.toString(),
            ),
        ).isEqualTo("SUPERSEDED")
    }

    @Test
    fun `activation during a response fences the old result even for the same processor revision`() {
        val id = seeds.confirmed("activate-again", "1.1", "owner-d")
        var changed = false
        provider.answerWith { incoming ->
            if (incoming.path == "/validate" && incoming.body.contains("activate-again") && !changed) {
                changed = true
                confirm(request())
            }
            parserAnswer(incoming)
        }
        val first = confirm(request())
        pump(first.jobId)
        assertThat(jobs.find(first.jobId)?.state).isEqualTo(JobState.SUPERSEDED)
        assertThat(result(id, "activation_id")).isNotEqualTo(first.id)
    }

    @Test
    fun `HTTP timeout retries the identical snapshot without UNKNOWN or session permits`() {
        val id = seeds.confirmed("timeout-once", "1.1", "owner-e")
        var timeouts = 0
        provider.answerWith { incoming ->
            val response = parserAnswer(incoming)
            if (incoming.path == "/validate" && incoming.body.contains("timeout-once") && timeouts++ == 0) {
                response.copy(delay = Duration.ofSeconds(3))
            } else {
                response
            }
        }
        val activation = confirm(request())
        pump(activation.jobId)
        val requests = provider.received.filter { it.path == "/validate" && it.body.contains("timeout-once") }
        assertThat(requests).hasSize(2)
        assertThat(requests.map { it.body }.distinct()).hasSize(1)
        assertThat(result(id, "valid")).isEqualTo(true)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM execution_permits WHERE job_id = ?",
                Int::class.java,
                activation.jobId,
            ),
        ).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parser_analysis_admission", Int::class.java)).isZero()
    }

    @Test
    fun `processor mismatch exhausts bounded item retries and retains no invalid result`() {
        val id = seeds.confirmed("wrong-build", "1.1", "owner-f")
        provider.answerWith { incoming ->
            val response = parserAnswer(incoming)
            if (incoming.path == "/validate" && incoming.body.contains("wrong-build")) {
                response.copy(headers = mapOf("Processor-Revision" to "sha256-v1:" + "b".repeat(64)))
            } else {
                response
            }
        }
        val activation = confirm(request())
        pump(activation.jobId)
        assertThat(result(id, "result_id")).isNull()
        assertThat(provider.received.count { it.path == "/validate" && it.body.contains("wrong-build") }).isEqualTo(5)
        assertThat(jobs.find(activation.jobId)?.state).isEqualTo(JobState.PARTIAL)
    }

    @Test
    fun `expired analysis admission is recoverable and an old token cannot release the new one`() {
        val first = claimedItem(confirm(request()).jobId)
        val second = claimedItem(confirm(request(version = "1.0")).jobId)
        val token = checkNotNull(admission.acquire(first.first, first.second.id))
        assertThat(capacity.held(first.first.jobId)).isNull()
        assertThat(admission.acquire(second.first, second.second.id)).isNull()
        assertThat(capacity.begin(second.first, second.second.id)).isNull()
        assertThat(capacity.canReserveManual()).isTrue()
        jdbc.update("UPDATE parser_analysis_admission SET expires_at = clock_timestamp() - INTERVAL '1 second'")
        val replacement = checkNotNull(admission.acquire(second.first, second.second.id))
        admission.release(token)
        assertThat(
            jdbc.queryForObject("SELECT token FROM parser_analysis_admission", UUID::class.java),
        ).isEqualTo(replacement)
        admission.release(replacement)
    }

    @Test
    fun `parallel activation retries create exactly one durable job`() {
        val request = request()
        val first =
            java.util.concurrent.CompletableFuture
                .supplyAsync { confirm(request) }
        val second =
            java.util.concurrent.CompletableFuture
                .supplyAsync { confirm(request) }
        assertThat(first.get().jobId).isEqualTo(second.get().jobId)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM parser_activations WHERE id = ?",
                Int::class.java,
                request.requestId,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `selected snapshot survives concurrent replacement and cleanup until the job finishes`() {
        val id = seeds.confirmed("pin-this-snapshot", "1.1", "owner-retention")
        val original = checkNotNull(seeds.reference(id))
        provider.answerWith { incoming ->
            if (incoming.path == "/validate" && incoming.body.contains("pin-this-snapshot")) {
                seeds.edit(id, "println(42);")
                cleanup.discard(original)
                assertThat(storage.read(original)).isEqualTo(ReadResult.Found("pin-this-snapshot"))
            }
            parserAnswer(incoming)
        }
        val activation = confirm(request())
        pump(activation.jobId)
        cleanup.discard(original)
        assertThat(storage.read(original)).isEqualTo(ReadResult.Missing)
        assertThat(result(id, "result_id")).isNull()
    }

    @Test
    fun `lost lease cannot publish and another consumer resumes the same item`() {
        val id = seeds.confirmed("lose-lease-once", "1.1", "owner-recovery")
        var lost = false
        var jobId: UUID? = null
        provider.answerWith { incoming ->
            if (incoming.path == "/validate" && incoming.body.contains("lose-lease-once") && !lost) {
                lost = true
                jdbc.update(
                    "UPDATE background_jobs SET lease_until = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                    jobId,
                )
            }
            parserAnswer(incoming)
        }
        val activation = confirm(request())
        jobId = activation.jobId
        pump(activation.jobId)
        assertThat(lost).isTrue()
        assertThat(result(id, "valid")).isEqualTo(true)
        assertThat(
            provider.received.count { it.path == "/validate" && it.body.contains("lose-lease-once") },
        ).isEqualTo(2)
    }

    private fun claimedItem(jobId: UUID): Pair<JobLease, WorkItem> {
        val lease = checkNotNull(checkNotNull(jobs.claim(jobId)).lease)
        jobs.select(
            lease = lease,
            items =
                listOf(
                    NewJobItem(
                        id = UUID.randomUUID(),
                        jobId = jobId,
                        key = UUID.randomUUID().toString(),
                        revisions = mapOf("code" to "test"),
                    ),
                ),
            cursor = null,
            complete = true,
        )
        return lease to jobs.items(lease).single()
    }

    private fun request(version: String = "1.1") =
        ParserActivationRequest(
            requestId = UUID.randomUUID(),
            scope = ParserScope("printscript", version),
            processor = parserRevision,
            reason = "approved test activation",
        )

    private fun confirm(request: ParserActivationRequest) =
        (activate.activate(request, administrator) as ParserActivationResult.Confirmed).activation

    private fun result(
        id: UUID,
        column: String,
    ): Any? =
        jdbc
            .queryForList(
                "SELECT $column FROM parser_validation_results WHERE snippet_id = ?",
                id,
            ).singleOrNull()
            ?.get(column)

    private fun publish() {
        OutboxPublisher(deliveries, RedisEventStream(redis, outbox), outbox).publishBatch()
    }

    private fun inbox(name: String) =
        RedisStreamInbox(redis = redis, outbox = outbox, settings = inboxSettings, consumer = name)

    private fun pump(jobId: UUID) {
        repeat(150) {
            jdbc.update("UPDATE background_jobs SET next_attempt_at = clock_timestamp() WHERE finished_at IS NULL")
            jdbc.update("UPDATE background_job_items SET next_attempt_at = clock_timestamp() WHERE state = 'RETRY'")
            publish()
            consumer.cycle(inbox("test-consumer"))
            if (jobs.find(jobId)?.state in
                setOf(JobState.COMPLETED, JobState.PARTIAL, JobState.SUPERSEDED, JobState.FAILED)
            ) {
                return
            }
            Thread.sleep(10)
        }
        error("Parser job did not finish")
    }

    companion object {
        private val provider = ScriptedHttpService(::parserAnswer)
        private val directory = Files.createTempDirectory("parser-revalidation-")

        @JvmStatic @DynamicPropertySource
        fun dependencies(registry: DynamicPropertyRegistry) {
            registry.add("language.printscript.base-url") { provider.baseUrl }
            registry.add("storage.local.directory") { directory.toString() }
        }

        @JvmStatic @AfterAll
        fun close() {
            provider.close()
        }
    }
}
