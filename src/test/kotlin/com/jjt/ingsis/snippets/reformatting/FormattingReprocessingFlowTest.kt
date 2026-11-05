package com.jjt.ingsis.snippets.reformatting

import com.jjt.ingsis.snippets.background.FindJobProgress
import com.jjt.ingsis.snippets.background.JobConsumer
import com.jjt.ingsis.snippets.background.JobLookup
import com.jjt.ingsis.snippets.background.JobProgress
import com.jjt.ingsis.snippets.background.JobState
import com.jjt.ingsis.snippets.background.OutboxPublisher
import com.jjt.ingsis.snippets.background.OutboxSettings
import com.jjt.ingsis.snippets.background.OutboxTestConfiguration
import com.jjt.ingsis.snippets.background.WorkerSettings
import com.jjt.ingsis.snippets.background.redis.RedisEventStream
import com.jjt.ingsis.snippets.background.redis.RedisStreamInbox
import com.jjt.ingsis.snippets.formattingrules.FormattedVariants
import com.jjt.ingsis.snippets.formattingrules.FormattingRulesOutcome
import com.jjt.ingsis.snippets.formattingrules.SaveFormattingRules
import com.jjt.ingsis.snippets.http.AccessLedger
import com.jjt.ingsis.snippets.http.ScriptedHttpService
import com.jjt.ingsis.snippets.http.StubRequest
import com.jjt.ingsis.snippets.http.StubResponse
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.metadata.JdbcEventDeliveries
import com.jjt.ingsis.snippets.metadata.JdbcJobProgress
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.time.Duration
import java.util.UUID

private const val MAX_CYCLES = 150
private const val PAUSE_MILLIS = 200L

private val revision = mapOf("Processor-Revision" to "sha256-v1:" + "e".repeat(64))

private val messages = JsonMapper.builder().build()

private const val CATALOG =
    """{"version":"1.1","rules":[{"id":"indent-inside-if","type":"integer","default":null,"minimum":0,""" +
        """"maximum":10,"conflictsWith":[],"description":"Sangría dentro de un if."}]}"""

private val operationFixture = OperationHttpFixture(revision.getValue("Processor-Revision"), ::legacyPrintScriptAnswer)

private fun printScriptAnswer(request: StubRequest): StubResponse =
    operationFixture.answer(request) ?: legacyPrintScriptAnswer(request)

private fun legacyPrintScriptAnswer(request: StubRequest): StubResponse =
    when (request.path) {
        "/formatting/rules" -> StubResponse(200, CATALOG, headers = revision)
        "/formatting/rules/check" -> StubResponse(200, """{"valid":true,"violations":[]}""", headers = revision)
        "/formatting" -> StubResponse(200, formatted(request), headers = revision)
        else -> StubResponse(404, "{}")
    }

private fun formatted(request: StubRequest): String {
    val code = messages.readTree(request.body).get("code").asString()
    return messages.writeValueAsString(
        mapOf(
            "formatted" to true,
            "code" to code.uppercase(),
            "diagnostics" to listOf<Any>(),
        ),
    )
}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "snippets.capacity.enabled=true", "snippets.worker.lease=5s",
        "snippets.worker.item-batch-size=2", "execution.recovery.delay-ms=3600000",
    ],
)
@ActiveProfiles("dev")
@Import(MetadataTestConfiguration::class, OutboxTestConfiguration::class, SnippetSeeds::class)
class FormattingReprocessingFlowTest(
    @Autowired private val save: SaveFormattingRules,
    @Autowired private val consumer: JobConsumer,
    @Autowired private val deliveries: JdbcEventDeliveries,
    @Autowired private val jobs: JdbcJobProgress,
    @Autowired private val progress: FindJobProgress,
    @Autowired private val variants: FormattedVariants,
    @Autowired private val seeds: SnippetSeeds,
    @Autowired private val redis: StringRedisTemplate,
) {
    private val settings = WorkerSettings(reclaimIdle = Duration.ofMillis(1))

    private val outbox = OutboxSettings()

    private val owner = "dev-" + UUID.randomUUID()

    @BeforeEach
    fun cleanStream() {
        redis.delete(outbox.stream)
        printScript.reset()
    }

    @Test
    fun `saving rules answers at once and the worker formats every owned snippet through Redis`() {
        val snippets = List(3) { index -> snippet("println($index);") }

        val job = saveRules()

        assertThat(formattingCalls()).isZero()
        assertThat(progressOf(job, owner)?.status).isEqualTo(JobState.PENDING)

        publishOutbox()
        assertThat(processUntilFinished(job, inbox("worker-1"))).isEqualTo(JobState.COMPLETED)

        snippets.forEachIndexed { index, id ->
            assertThat(seeds.code(checkNotNull(variants.find(id)).derived)).isEqualTo("PRINTLN($index);")
        }
        assertThat(progressOf(job, owner))
            .isEqualTo(
                JobProgress(
                    id = job,
                    status = JobState.COMPLETED,
                    processed = 3,
                    failed = 0,
                    pending = 0,
                    selectionComplete = true,
                ),
            )
        assertThat(progressOf(job, "dev-" + UUID.randomUUID())).isNull()
    }

    @Test
    fun `a duplicated delivery creates no new results`() {
        val id = snippet("println(1);")
        val job = saveRules()
        publishOutbox()
        val inbox = inbox("worker-1")
        processUntilFinished(job, inbox)
        val published = variants.find(id)
        val calls = formattingCalls()

        RedisEventStream(redis, outbox).publish(checkNotNull(jobs.find(job)).event)
        consumer.cycle(inbox)

        assertThat(variants.find(id)).isEqualTo(published)
        assertThat(formattingCalls()).isEqualTo(calls)
        assertThat(redis.opsForStream<String, String>().pending(outbox.stream, settings.group)?.totalPendingMessages)
            .isZero()
    }

    @Test
    fun `another worker resumes a half done batch without formatting finished snippets again`() {
        val snippets = List(3) { index -> snippet("println($index);") }
        val job = saveRules()
        publishOutbox()
        val first = inbox("worker-1")
        repeat(MAX_CYCLES) {
            if (snippets.any { id -> variants.find(id) != null }) return@repeat
            consumer.cycle(first)
            Thread.sleep(PAUSE_MILLIS)
        }

        assertThat(processUntilFinished(job, inbox("worker-2"))).isEqualTo(JobState.COMPLETED)

        assertThat(snippets.map { variants.find(it) }).doesNotContainNull()
        assertThat(formattingCalls()).isEqualTo(3)
    }

    @Test
    fun `a fenced UNKNOWN retry finishes after the original Redis delivery was acknowledged`() {
        val snippet = snippet("println(1);")
        val job = saveRules()
        printScript.answerWith { request ->
            if (request.method == "PUT") StubResponse(503, "{}") else printScriptAnswer(request)
        }
        publishOutbox()
        val queue = inbox("uncertain-start")
        consumer.cycle(queue)
        assertThat(jobs.find(job)?.state).isEqualTo(JobState.UNKNOWN)
        assertThat(
            redis.opsForStream<String, String>().pending(outbox.stream, settings.group)?.totalPendingMessages,
        ).isZero()
        val original = printScript.received.first { it.method == "PUT" }.path
        printScript.reset()
        assertThat(processUntilFinished(job, queue)).isEqualTo(JobState.COMPLETED)
        assertThat(variants.find(snippet)).isNotNull()
        assertThat(printScript.received.any { it.path == "$original/cancel" }).isTrue()
        assertThat(formattingCalls()).isEqualTo(1)
        assertThat(jobs.progress(job)?.processed).isEqualTo(1)
    }

    private fun saveRules(): UUID {
        val outcome =
            save.save(
                language = "printscript",
                version = "1.1",
                rules = mapOf("indent-inside-if" to 2),
                context = ActorContext(listOf(owner)),
            )
        return checkNotNull((outcome as FormattingRulesOutcome.Shown).view.jobId)
    }

    private fun publishOutbox() {
        OutboxPublisher(deliveries, RedisEventStream(redis, outbox), outbox).publishBatch()
    }

    private fun processUntilFinished(
        job: UUID,
        inbox: RedisStreamInbox,
    ): JobState {
        repeat(MAX_CYCLES) {
            consumer.cycle(inbox)
            val state = checkNotNull(jobs.find(job)).state
            if (state in finished) return state
            Thread.sleep(PAUSE_MILLIS)
        }
        error("The batch did not finish")
    }

    private fun inbox(name: String): RedisStreamInbox =
        RedisStreamInbox(redis = redis, outbox = outbox, settings = settings, consumer = name)

    private fun progressOf(
        job: UUID,
        actor: String,
    ): JobProgress? = (progress.find(job.toString(), ActorContext(listOf(actor))) as? JobLookup.Found)?.progress

    private fun snippet(code: String): UUID = seeds.confirmed(code, "1.1", owner).also { ledger.own(owner, it) }

    private fun formattingCalls(): Int =
        printScript.received.count {
            it.path.startsWith("/operations/") &&
                it.method == "PUT"
        }

    companion object {
        private val finished = setOf(JobState.COMPLETED, JobState.PARTIAL, JobState.FAILED, JobState.SUPERSEDED)

        private val ledger = AccessLedger()

        private val permissions = ScriptedHttpService { request -> ledger.answer(request) }

        private val printScript = ScriptedHttpService(::printScriptAnswer)

        @JvmStatic
        @DynamicPropertySource
        fun dependencies(registry: DynamicPropertyRegistry) {
            registry.add("permissions.base-url") { permissions.baseUrl }
            registry.add("language.printscript.base-url") { printScript.baseUrl }
            registry.add("storage.local.directory") { Files.createTempDirectory("reformatting-flow").toString() }
        }

        @JvmStatic
        @AfterAll
        fun stopDependencies() {
            permissions.close()
            printScript.close()
        }
    }
}
