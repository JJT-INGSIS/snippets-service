package com.jjt.ingsis.snippets.reformatting

import com.jjt.ingsis.snippets.background.ConfirmedJobChange
import com.jjt.ingsis.snippets.background.JobState
import com.jjt.ingsis.snippets.background.JobStep
import com.jjt.ingsis.snippets.background.JobWork
import com.jjt.ingsis.snippets.background.NewJobItem
import com.jjt.ingsis.snippets.background.RemoteExecution
import com.jjt.ingsis.snippets.background.newJob
import com.jjt.ingsis.snippets.formattingrules.FormattedVariants
import com.jjt.ingsis.snippets.formattingrules.FormattingRulesChangedJob
import com.jjt.ingsis.snippets.formattingrules.FormattingScope
import com.jjt.ingsis.snippets.formattingrules.FormattingSelection
import com.jjt.ingsis.snippets.formattingrules.RulesReplacement
import com.jjt.ingsis.snippets.http.AccessLedger
import com.jjt.ingsis.snippets.http.ScriptedHttpService
import com.jjt.ingsis.snippets.http.StubRequest
import com.jjt.ingsis.snippets.http.StubResponse
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.metadata.AtomicJobWrite
import com.jjt.ingsis.snippets.metadata.JdbcExecutionCapacity
import com.jjt.ingsis.snippets.metadata.JdbcFormattingRules
import com.jjt.ingsis.snippets.metadata.JdbcJobProgress
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.storage.ContentReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.util.UUID

private const val MAX_TURNS = 30

private val processor = ProcessorRevision("sha256-v1:" + "d".repeat(64))

private val indentTwo = FormattingSelection(mapOf("indent-inside-if" to 2))

private val requests = JsonMapper.builder().build()

private val operationFixture = OperationHttpFixture(processor.value, ::legacyPrintScriptAnswer)

private fun printScriptAnswer(request: StubRequest): StubResponse =
    operationFixture.answer(request) ?: legacyPrintScriptAnswer(request)

private fun legacyPrintScriptAnswer(request: StubRequest): StubResponse {
    val code = requests.readTree(request.body).get("code").asString()
    val body =
        if (code.contains("BAD")) {
            """{"formatted":false,"code":null,"diagnostics":[""" +
                """{"rule":"UNTERMINATED_STRING","message":"unterminated","line":1,"column":1}]}"""
        } else {
            requests.writeValueAsString(
                mapOf(
                    "formatted" to true,
                    "code" to code.uppercase(),
                    "diagnostics" to listOf<Any>(),
                ),
            )
        }
    return StubResponse(200, body, headers = mapOf("Processor-Revision" to processor.value))
}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "snippets.capacity.enabled=true", "snippets.worker.lease=5s", "snippets.worker.item-batch-size=2",
        "execution.recovery.delay-ms=3600000",
    ],
)
@Import(MetadataTestConfiguration::class, SnippetSeeds::class)
class FormattingRulesChangedHandlerTest(
    @Autowired private val handler: FormattingRulesChangedHandler,
    @Autowired private val jobs: JdbcJobProgress,
    @Autowired private val capacity: JdbcExecutionCapacity,
    @Autowired private val atomic: AtomicJobWrite,
    @Autowired private val rules: JdbcFormattingRules,
    @Autowired private val variants: FormattedVariants,
    @Autowired private val seeds: SnippetSeeds,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val owner = "dev-" + UUID.randomUUID()

    @BeforeEach
    fun restoreDependencies() {
        jdbc.update("DELETE FROM execution_permits WHERE kind = 'BACKGROUND'")
        printScript.reset()
        permissions.reset()
    }

    @Test
    fun `new rules give every owned snippet of that version a current variant and keep the originals`() {
        val formatted = List(3) { index -> snippet("println($index);") }
        val otherVersion = snippet("println(9);", version = "1.0")
        val otherOwner = snippet("println(8);", ownerId = "dev-" + UUID.randomUUID())
        val job = changeRules(indentTwo)

        assertThat(runUntilFinished(job)).isEqualTo(JobState.COMPLETED)

        formatted.forEachIndexed { index, id ->
            val variant = checkNotNull(variants.find(id))
            assertThat(code(variant.derived)).isEqualTo("PRINTLN($index);")
            assertThat(variant.basis.rules).isEqualTo(indentTwo.revision)
            assertThat(variant.basis.processor).isEqualTo(processor)
            assertThat(code(checkNotNull(seeds.reference(id)))).isEqualTo("println($index);")
        }
        assertThat(variants.find(otherVersion)).isNull()
        assertThat(variants.find(otherOwner)).isNull()
        assertThat(jobs.progress(job)?.processed).isEqualTo(3L)
    }

    @Test
    fun `a turn that dies is resumed and no snippet is formatted twice`() {
        val snippets = List(3) { index -> snippet("println($index);") }
        val job = changeRules(indentTwo)
        turn(job)
        val dead = claim(job)
        dead.items()
        expireLease(job)

        assertThat(runUntilFinished(job)).isEqualTo(JobState.COMPLETED)

        assertThat(snippets.map { variants.find(it) }).doesNotContainNull()
        assertThat(formattingCalls()).isEqualTo(3)
    }

    @Test
    fun `a snippet that cannot be formatted fails alone and the batch ends partial`() {
        val good = snippet("println(1);")
        val bad = snippet("BAD")
        val job = changeRules(indentTwo)

        assertThat(runUntilFinished(job)).isEqualTo(JobState.PARTIAL)

        assertThat(variants.find(good)).isNotNull()
        assertThat(variants.find(bad)).isNull()
        assertThat(itemReason(job, bad)).isEqualTo("UNFORMATTABLE")
    }

    @Test
    fun `newer rules supersede the old batch and only the newer one publishes`() {
        val id = snippet("println(1);")
        val old = changeRules(indentTwo)
        val indentFour = FormattingSelection(mapOf("indent-inside-if" to 4))
        val newer = changeRules(indentFour)

        assertThat(runUntilFinished(old)).isEqualTo(JobState.SUPERSEDED)
        assertThat(variants.find(id)).isNull()

        assertThat(runUntilFinished(newer)).isEqualTo(JobState.COMPLETED)
        assertThat(variants.find(id)?.basis?.rules).isEqualTo(indentFour.revision)
    }

    @Test
    fun `an edit while formatting never publishes the old code`() {
        val id = snippet("println(1);")
        val job = changeRules(indentTwo)
        printScript.answerWith { request ->
            if (formattingCalls() == 1) edit(id, "println(2);")
            printScriptAnswer(request)
        }

        assertThat(runUntilFinished(job)).isEqualTo(JobState.COMPLETED)

        val variant = checkNotNull(variants.find(id))
        assertThat(code(variant.derived)).isEqualTo("PRINTLN(2);")
        assertThat(variant.basis.content).isEqualTo(seeds.reference(id))
    }

    @Test
    fun `dependencies that are down publish nothing and the batch resumes later`() {
        val id = snippet("println(1);")
        val job = changeRules(indentTwo)
        permissions.answerWith { StubResponse(500, """{"status":500}""") }

        assertThat(turn(job)).isEqualTo(JobStep.Retry("PERMISSIONS_UNAVAILABLE"))

        permissions.reset()
        printScript.answerWith { StubResponse(500, """{"status":500}""") }
        turn(job)

        assertThat(itemReason(job, id)).isEqualTo("LANGUAGE_UNAVAILABLE")
        assertThat(capacity.held(job)).isNull()
        assertThat(variants.find(id)).isNull()

        printScript.reset()
        assertThat(runUntilFinished(job)).isEqualTo(JobState.COMPLETED)
        assertThat(variants.find(id)).isNotNull()
    }

    @Test
    fun `without background capacity the turn yields without calling PrintScript`() {
        snippet("println(1);")
        val job = changeRules(indentTwo)
        occupyBackgroundCapacity()

        assertThat(turn(job)).isEqualTo(JobStep.Yield)

        assertThat(formattingCalls()).isZero()
    }

    @Test
    fun `an interrupted format releases its permit and the snippet is formatted again`() {
        val id = snippet("println(1);")
        val job = changeRules(indentTwo)
        val interrupted = claim(job)
        interrupted.select(listOf(itemFor(job, id)), cursor = id.toString(), complete = true)
        val reserved = checkNotNull(interrupted.beginRemote(interrupted.items().single()))
        interrupted.bindRemote(
            reserved.id,
            RemoteExecution(
                id = reserved.id.toString(),
                processor = processor.value,
                activeLimit = 4L,
                context = mapOf("kind" to "FORMAT", "version" to "1.1"),
            ),
        )
        expireLease(job)

        assertThat(turn(job)).isEqualTo(JobStep.Yield)
        assertThat(capacity.held(job)).isNull()
        assertThat(itemReason(job, id)).isEqualTo("REMOTE_OPERATION_CANCELLED")

        assertThat(runUntilFinished(job)).isEqualTo(JobState.COMPLETED)
        assertThat(variants.find(id)).isNotNull()
    }

    @Test
    fun `a legacy synchronous attempt without a known ID keeps UNKNOWN until termination is independently proven`() {
        val id = snippet("println(1);")
        val job = changeRules(indentTwo)
        val failed = claim(job)
        failed.select(listOf(itemFor(job, id)), cursor = id.toString(), complete = true)
        checkNotNull(failed.beginRemote(failed.items().single()))
        jobs.finish(failed.lease, JobStep.Retry("HANDLER_FAILURE"))

        assertThat(checkNotNull(jobs.find(job)).state).isEqualTo(JobState.UNKNOWN)
        assertThat(turn(job)).isEqualTo(JobStep.Unknown("LEGACY_REMOTE_PROOF_REQUIRED"))
        assertThat(capacity.held(job)).isNotNull()
        assertThat(variants.find(id)).isNull()
    }

    private fun runUntilFinished(job: UUID): JobState {
        repeat(MAX_TURNS) {
            val state = checkNotNull(jobs.find(job)).state
            if (state in finished) return state
            turn(job)
        }
        error("The job did not finish in $MAX_TURNS turns")
    }

    private fun turn(job: UUID): JobStep {
        val work = claim(job)
        val permit = capacity.held(job)
        val step =
            if (permit != null || work.job.state == JobState.UNKNOWN) {
                handler.reconcile(work, permit)
            } else {
                handler.step(work)
            }
        jobs.finish(work.lease, step)
        return step
    }

    private fun claim(job: UUID): JobWork {
        jdbc.update("UPDATE background_jobs SET next_attempt_at = clock_timestamp() WHERE id = ?", job)
        jdbc.update("UPDATE background_job_items SET next_attempt_at = clock_timestamp() WHERE job_id = ?", job)
        return JobWork(checkNotNull(jobs.claim(job)), jobs, capacity)
    }

    private fun expireLease(job: UUID) {
        jdbc.update(
            "UPDATE background_jobs SET lease_until = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
            job,
        )
    }

    private fun changeRules(selection: FormattingSelection): UUID {
        val scope = FormattingScope(owner, "printscript", "1.1")
        val replacement =
            rules.replace(scope, selection) { configurationId ->
                FormattingRulesChangedJob.of(
                    scope = scope,
                    configurationId = configurationId,
                    selection = selection,
                    processor = processor,
                )
            }
        return (replacement as RulesReplacement.Changed).jobId
    }

    private fun snippet(
        code: String,
        version: String = "1.1",
        ownerId: String = owner,
    ): UUID = seeds.confirmed(code, version, ownerId).also { ledger.own(ownerId, it) }

    private fun edit(
        id: UUID,
        code: String,
    ) = seeds.edit(id, code)

    private fun code(reference: ContentReference): String = seeds.code(reference)

    private fun itemFor(
        job: UUID,
        id: UUID,
    ): NewJobItem =
        NewJobItem(id = UUID.randomUUID(), jobId = job, key = id.toString(), revisions = mapOf("code" to "x"))

    private fun occupyBackgroundCapacity() {
        val other = newJob()
        atomic.commit { ConfirmedJobChange(Unit, other) }
        val work = claim(other.event.jobId)
        work.select(listOf(itemFor(other.event.jobId, UUID.randomUUID())), cursor = null, complete = true)
        checkNotNull(work.beginRemote(work.items().single()))
    }

    private fun itemReason(
        job: UUID,
        id: UUID,
    ): String? =
        jdbc.queryForObject(
            "SELECT last_error FROM background_job_items WHERE job_id = ? AND item_key = ?",
            String::class.java,
            job,
            id.toString(),
        )

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
            registry.add("storage.local.directory") { Files.createTempDirectory("reformatting").toString() }
        }

        @JvmStatic
        @AfterAll
        fun stopDependencies() {
            permissions.close()
            printScript.close()
        }
    }
}
