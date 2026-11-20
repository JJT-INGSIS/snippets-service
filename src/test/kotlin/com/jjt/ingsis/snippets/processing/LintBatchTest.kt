package com.jjt.ingsis.snippets.processing

import com.jjt.ingsis.snippets.background.JobState
import com.jjt.ingsis.snippets.background.JobStep
import com.jjt.ingsis.snippets.background.JobWork
import com.jjt.ingsis.snippets.formattingrules.RulesReplacement
import com.jjt.ingsis.snippets.http.AccessLedger
import com.jjt.ingsis.snippets.http.ScriptedHttpService
import com.jjt.ingsis.snippets.http.StubRequest
import com.jjt.ingsis.snippets.http.StubResponse
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.lintingrules.LintingRulesChangedHandler
import com.jjt.ingsis.snippets.lintingrules.LintingRulesChangedJob
import com.jjt.ingsis.snippets.lintingrules.LintingScope
import com.jjt.ingsis.snippets.lintingrules.LintingSelection
import com.jjt.ingsis.snippets.metadata.JdbcExecutionCapacity
import com.jjt.ingsis.snippets.metadata.JdbcJobProgress
import com.jjt.ingsis.snippets.metadata.JdbcLintingRules
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.reformatting.SnippetSeeds
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val processor = ProcessorRevision("sha256-v1:" + "b".repeat(64))
private val documents = JsonMapper.builder().build()
private val pending = AtomicBoolean()
private val wrongRules = AtomicBoolean()
private val operations = ConcurrentHashMap<String, String>()
private val header = mapOf("Processor-Revision" to processor.value, "Execution-Active-Limit" to "4")

private fun answer(request: StubRequest): StubResponse {
    if (request.path == "/linting/rules") {
        return StubResponse(
            200,
            """{"version":"1.1","rules":[],"defaults":{}}""",
            headers = header,
        )
    }
    val id = request.path.substringAfter("/operations/")
    if (request.method == "PUT") {
        val command = documents.readTree(request.body)
        val findings =
            if (command.get("code").asString().contains("1+2")) {
                listOf(
                    mapOf(
                        "rule" to "mandatory-variable-or-literal-in-println",
                        "message" to "Sólo literal o variable",
                        "line" to 1,
                        "column" to 1,
                    ),
                )
            } else {
                emptyList()
            }
        operations[id] =
            documents.writeValueAsString(
                mapOf(
                    "id" to id,
                    "command" to command,
                    "state" to "COMPLETED",
                    "output" to mapOf("outcome" to "LINTED", "diagnostics" to findings),
                ),
            )
    }
    val original = operations[id] ?: return StubResponse(404, "{}")
    val stored =
        if (wrongRules.get()) {
            original.replace(
                "\"mandatory-variable-or-literal-in-println\":true",
                "\"mandatory-variable-or-literal-in-println\":false",
            )
        } else {
            original
        }
    return StubResponse(200, if (pending.get()) stored.replace("COMPLETED", "RUNNING") else stored, headers = header)
}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "snippets.capacity.enabled=true", "snippets.worker.lease=5s", "snippets.worker.item-batch-size=2",
    ],
)
@Import(MetadataTestConfiguration::class, SnippetSeeds::class)
class LintBatchTest(
    @Autowired private val handler: LintingRulesChangedHandler,
    @Autowired private val jobs: JdbcJobProgress,
    @Autowired private val capacity: JdbcExecutionCapacity,
    @Autowired private val rules: JdbcLintingRules,
    @Autowired private val seeds: SnippetSeeds,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val owner = "lint-owner-${UUID.randomUUID()}"
    private val scope = LintingScope(owner, "printscript", "1.1")
    private val selection = LintingSelection(mapOf("mandatory-variable-or-literal-in-println" to true))

    @BeforeEach
    fun reset() {
        jdbc.update("DELETE FROM execution_permits WHERE kind = 'BACKGROUND'")
        pending.set(false)
        wrongRules.set(false)
        provider.reset()
    }

    @Test
    fun `batch evaluates only the current owners language version and persists diagnostics without altering code`() {
        val passed = snippet("println(1);")
        val rejected = snippet("println(1+2);")
        val otherVersion = snippet("println(1);", version = "1.0")
        val otherOwner = snippet("println(1);", actor = "other-${UUID.randomUUID()}")
        val job = change(selection)
        assertThat(finish(job)).isEqualTo(JobState.COMPLETED)
        assertThat(state(passed)).isEqualTo("COMPLIANT")
        assertThat(state(rejected)).isEqualTo("NON_COMPLIANT")
        assertThat(state(otherVersion)).isNull()
        assertThat(state(otherOwner)).isNull()
        assertThat(jobs.progress(job)?.processed).isEqualTo(2)
        assertThat(seeds.code(checkNotNull(seeds.reference(rejected)))).isEqualTo("println(1+2);")
        val diagnostics =
            jdbc.queryForObject(
                "SELECT diagnostics::text FROM snippet_lint_assessments WHERE snippet_id = ?",
                String::class.java,
                rejected,
            )
        assertThat(diagnostics).contains("mandatory-variable-or-literal-in-println", "Sólo literal o variable")
    }

    @Test
    fun `a running remote operation retains its durable permit and recovery never sends a second start`() {
        val snippet = snippet("println(1);")
        val job = change(selection)
        pending.set(true)
        assertThat(turn(job)).isEqualTo(JobStep.Unknown("REMOTE_OPERATION_RUNNING"))
        val permit = checkNotNull(capacity.held(job))
        assertThat(permit.remote?.id).isEqualTo(permit.id.toString())
        assertThat(state(snippet)).isNull()
        assertThat(turn(job)).isEqualTo(JobStep.Unknown("REMOTE_OPERATION_RUNNING"))
        assertThat(capacity.held(job)?.id).isEqualTo(permit.id)
        pending.set(false)
        assertThat(finish(job)).isEqualTo(JobState.COMPLETED)
        assertThat(capacity.held(job)).isNull()
        assertThat(provider.received.count { it.method == "PUT" }).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM snippet_lint_assessments " +
                    "WHERE snippet_id = ?",
                Int::class.java,
                snippet,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `new rules supersede the old job before starting remote analysis`() {
        snippet("println(1+2);")
        val old = change(selection)
        val current = change(LintingSelection.EMPTY)
        assertThat(finish(old)).isEqualTo(JobState.SUPERSEDED)
        assertThat(provider.received.none { it.method == "PUT" }).isTrue()
        assertThat(finish(current)).isEqualTo(JobState.COMPLETED)
    }

    @Test
    fun `an operation result with different effective rules cannot be published as the requested analysis`() {
        val snippet = snippet("println(1+2);")
        val job = change(selection)
        wrongRules.set(true)
        assertThat(turn(job)).isEqualTo(JobStep.Unknown("REMOTE_INPUT_MISMATCH"))
        assertThat(state(snippet)).isNull()
        assertThat(capacity.held(job)).isNotNull()
        wrongRules.set(false)
        assertThat(finish(job)).isEqualTo(JobState.COMPLETED)
        assertThat(state(snippet)).isEqualTo("NON_COMPLIANT")
        assertThat(provider.received.count { it.method == "PUT" }).isEqualTo(1)
    }

    private fun snippet(
        code: String,
        version: String = "1.1",
        actor: String = owner,
    ): UUID = seeds.confirmed(code, version, actor).also { ledger.own(actor, it) }

    private fun change(selection: LintingSelection): UUID =
        (
            rules.replace(scope, selection) {
                LintingRulesChangedJob.of(
                    scope = scope,
                    configurationId = it,
                    selection = selection,
                    processor = processor,
                )
            } as com.jjt.ingsis.snippets.lintingrules.RulesReplacement.Changed
        ).jobId

    private fun state(id: UUID): String? =
        jdbc
            .queryForList(
                "SELECT a.state FROM snippets s LEFT JOIN snippet_lint_assessments a " +
                    "ON a.id=s.current_lint_assessment WHERE s.id=?",
                id,
            ).single()["state"] as? String

    private fun turn(id: UUID): JobStep {
        jdbc.update(
            "UPDATE background_jobs SET next_attempt_at=clock_timestamp()-INTERVAL '1 second', " +
                "lease_until=NULL, lease_token=NULL WHERE id=?",
            id,
        )
        val work = JobWork(checkNotNull(jobs.claim(id)), jobs, capacity)
        val permit = capacity.held(id)
        val result = if (permit != null) handler.reconcile(work, permit) else handler.step(work)
        jobs.finish(work.lease, result)
        return result
    }

    private fun finish(id: UUID): JobState {
        repeat(20) {
            val state = checkNotNull(jobs.find(id)).state
            if (state in setOf(JobState.COMPLETED, JobState.FAILED, JobState.PARTIAL, JobState.SUPERSEDED)) return state
            turn(id)
        }
        error("Lint job did not finish")
    }

    companion object {
        private val ledger = AccessLedger()
        private val permissions = ScriptedHttpService(ledger::answer)
        private val provider = ScriptedHttpService(::answer)

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("permissions.base-url") { permissions.baseUrl }
            registry.add("language.printscript.base-url") { provider.baseUrl }
            registry.add("storage.local.directory") { Files.createTempDirectory("lint-batch").toString() }
        }

        @JvmStatic @AfterAll
        fun stop() {
            provider.close()
            permissions.close()
        }
    }
}
