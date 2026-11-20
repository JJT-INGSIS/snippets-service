package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.http.AccessLedger
import com.jjt.ingsis.snippets.http.ScriptedHttpService
import com.jjt.ingsis.snippets.http.json
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StoreResult
import com.jjt.ingsis.snippets.testcase.TestCaseCreation
import com.jjt.ingsis.snippets.testcase.TestCaseDefinition
import com.jjt.ingsis.snippets.testcase.TestCaseStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.UUID

/** Actual interpreter + application HTTP + PostgreSQL. Permissions is an HTTP contract provider;
 * full UI/Permissions/Asset acceptance remains SNI-53. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["execution.recovery.delay-ms=3600000"],
)
@ActiveProfiles("dev")
@Import(MetadataTestConfiguration::class)
@EnabledIfEnvironmentVariable(named = "PRINTSCRIPT_EXECUTION_URL", matches = ".+")
@Timeout(30)
class TestExecutionLiveTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") private val port: Int,
        private val jdbc: JdbcTemplate,
        private val storage: SnippetContentStorage,
        private val definitions: TestCaseStore,
        private val audit: ExecutionAudit,
    ) {
        private val http = HttpClient.newHttpClient()

        @Test
        fun `real interpreter results distinguish exact success differences input exhaustion and program errors`() {
            val cases =
                listOf(
                    Case(
                        code = "println(\"ñ\"); println(\"ñ\"); println(\"\");",
                        inputs = emptyList(),
                        expected = listOf("ñ", "ñ", ""),
                        outcome = "COMPLETED",
                        comparison = "PASSED",
                    ),
                    Case(
                        code = "println(\"ñ\");",
                        inputs = emptyList(),
                        expected = listOf("ñ "),
                        outcome = "COMPLETED",
                        comparison = "FAILED",
                    ),
                    Case(
                        code = "let a: string = readInput(\"?\");",
                        inputs = emptyList(),
                        expected = emptyList(),
                        outcome = "INPUT_EXHAUSTED",
                        comparison = null,
                    ),
                    Case(
                        code = "println(1 / 0);",
                        inputs = emptyList(),
                        expected = emptyList(),
                        outcome = "PROGRAM_ERROR",
                        comparison = null,
                    ),
                )
            cases.forEach(::execute)
        }

        @Test
        fun `real ordered inputs and output preserve duplicates and empty values in the durable result`() {
            execute(
                Case(
                    code =
                        "let a: string = readInput(\"a?\"); println(a); " +
                            "let b: string = readInput(\"b?\"); println(b);",
                    inputs = listOf("", "ñ"),
                    expected = listOf("", "ñ"),
                    outcome = "COMPLETED",
                    comparison = "PASSED",
                ),
            )
        }

        private fun execute(case: Case) {
            val snippetId = UUID.randomUUID()
            val actor = "live-${UUID.randomUUID()}"
            val reference = (storage.store(case.code) as StoreResult.Stored).reference
            jdbc.update(
                "INSERT INTO snippets (id, name, language, version, state, creation_key, creation_actor_id, " +
                    "creation_fingerprint, content_reference) " +
                    "VALUES (?, 'live', 'printscript', '1.1', 'CONFIRMED', ?, ?, 'f', ?)",
                snippetId,
                UUID.randomUUID(),
                actor,
                reference.value,
            )
            val definition = TestCaseDefinition(case.inputs, case.expected)
            val test = definitions.create(snippetId, definition, 50) as TestCaseCreation.Created
            ledger.own(actor, snippetId)
            val start =
                http.send(
                    request("/snippets/$snippetId/tests/${test.testCase.id}/executions", actor)
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertThat(start.statusCode()).describedAs(start.body()).isEqualTo(201)
            val descriptor = json.readTree(start.body())
            val id = UUID.fromString(descriptor.path("executionId").asString())
            val stream =
                http.send(
                    request("/executions/$id/events?after=0", actor).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
            assertThat(stream.statusCode()).isEqualTo(200)
            val events = stream.body().bufferedReader().use { it.lineSequence().map(json::readTree).toList() }
            assertThat(events.first().path("type").asString()).isEqualTo("started")
            assertThat(events.map { it.path("executionId").asString() }).containsOnly(id.toString())
            val terminal = events.last().path("data")
            assertThat(terminal.path("outcome").asString()).isEqualTo(case.outcome)
            if (case.comparison == null) {
                assertThat(terminal.has("comparison")).isFalse()
            } else {
                assertThat(terminal.path("comparison").asString()).isEqualTo(case.comparison)
            }
            val saved = checkNotNull(audit.find(id))
            assertThat(saved.snapshot.code).isEqualTo(case.code)
            assertThat(saved.snapshot.test?.definition).isEqualTo(definition)
            assertThat(
                saved.completed
                    ?.terminal
                    ?.outcome
                    ?.name,
            ).isEqualTo(case.outcome)
            assertThat(saved.reserved).isFalse()
        }

        private fun request(
            path: String,
            actor: String,
        ): HttpRequest.Builder =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Dev-Actor-Id", actor)

        private data class Case(
            val code: String,
            val inputs: List<String>,
            val expected: List<String>,
            val outcome: String,
            val comparison: String?,
        )

        companion object {
            private val ledger = AccessLedger()
            private val permissions = ScriptedHttpService(ledger::answer)

            @JvmStatic @DynamicPropertySource
            fun properties(registry: DynamicPropertyRegistry) {
                registry.add("permissions.base-url") { permissions.baseUrl }
                registry.add("storage.local.directory") { Files.createTempDirectory("test-execution-live").toString() }
                registry.add(
                    "language.printscript.base-url",
                ) { checkNotNull(System.getenv("PRINTSCRIPT_EXECUTION_URL")) }
            }

            @JvmStatic @AfterAll
            fun stop() {
                permissions.close()
            }
        }
    }
