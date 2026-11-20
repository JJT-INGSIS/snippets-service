package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.http.AccessLedger
import com.jjt.ingsis.snippets.http.ScriptedHttpService
import com.jjt.ingsis.snippets.http.json
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.language.execution.ExecutionState
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StoreResult
import com.jjt.ingsis.snippets.testcase.TestCaseCreation
import com.jjt.ingsis.snippets.testcase.TestCaseDefinition
import com.jjt.ingsis.snippets.testcase.TestCaseStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["execution.recovery.delay-ms=3600000"],
)
@ActiveProfiles("dev")
@MockitoBean(types = [Language::class])
@Import(MetadataTestConfiguration::class)
class TestExecutionHttpTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") private val port: Int,
        private val jdbc: JdbcTemplate,
        private val storage: SnippetContentStorage,
        private val definitions: TestCaseStore,
        private val audit: ExecutionAudit,
        private val language: Language,
    ) {
        private val executor = ControlledExecutor()
        private val http = HttpClient.newHttpClient()

        @BeforeEach
        fun prepare() {
            permissions.reset()
            `when`(language.executorFor("printscript")).thenReturn(ExecutionResult.Success(executor))
        }

        @Test
        fun `public stream flushes output before terminal and persists the exact original snapshot and result`() {
            executor.script.set(ExecutionScript(outputs = listOf("early", "ñ"), release = CountDownLatch(1)))
            val fixture = stored(expected = listOf("early", "ñ"))
            val started = post(fixture.startPath, fixture.actor)
            assertThat(started.statusCode()).isEqualTo(201)
            val descriptor = json.readTree(started.body())
            val id = descriptor.path("executionId").asString()
            assertThat(audit.find(UUID.fromString(id))?.remote).isNotNull()
            assertThat(descriptor.has("actorId")).isFalse()
            assertThat(descriptor.path("snapshot").has("code")).isFalse()
            val response =
                http.send(
                    request("/executions/$id/events?after=0", fixture.actor).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
            assertThat(response.statusCode()).isEqualTo(200)
            response.body().bufferedReader().use { input ->
                assertThat(json.readTree(input.readLine()).path("type").asString()).isEqualTo("started")
                val output = json.readTree(input.readLine())
                assertThat(output.path("data").path("text").asString()).isEqualTo("early")
                assertThat(output.path("executionId").asString()).isEqualTo(id)
                assertThat(audit.find(UUID.fromString(id))?.completed).isNull()
                // A later edit does not replace the historical code or expectations being executed.
                jdbc.update("UPDATE snippets SET version = '1.0' WHERE id = ?", fixture.snippetId)
                jdbc.update(
                    "UPDATE snippet_test_cases SET expected_outputs = '[\"changed\"]' WHERE id = ?",
                    fixture.testId,
                )
                executor.script
                    .get()
                    .release
                    .countDown()
                val remaining = input.lineSequence().map(json::readTree).toList()
                assertThat(
                    remaining
                        .last()
                        .path("data")
                        .path("comparison")
                        .asString(),
                ).isEqualTo("PASSED")
            }
            val stored = checkNotNull(audit.find(UUID.fromString(id)))
            assertThat(stored.snapshot.code).isEqualTo("println(1);")
            assertThat(stored.snapshot.version).isEqualTo("1.1")
            assertThat(checkNotNull(stored.snapshot.test).definition.expectedOutputs).containsExactly("early", "ñ")
            assertThat(stored.completed?.outputs).containsExactly("early", "ñ")
            assertThat(stored.reserved).isFalse()
        }

        @Test
        fun `an owner and a shared reader may execute but cannot control each others sessions`() {
            val fixture = stored()
            val recipient = "recipient-${UUID.randomUUID()}"
            ledger.share(fixture.actor, fixture.snippetId, recipient)
            val started = post(fixture.startPath, recipient)
            assertThat(started.statusCode()).isEqualTo(201)
            val id = json.readTree(started.body()).path("executionId").asString()
            assertThat(get("/executions/$id", fixture.actor).statusCode()).isEqualTo(403)
            assertThat(post("/executions/$id/cancel", fixture.actor).statusCode()).isEqualTo(403)
            assertThat(get("/executions/$id/events?after=0", fixture.actor).statusCode()).isEqualTo(403)
            assertThat(get("/executions/$id", recipient).statusCode()).isEqualTo(200)
            permissions.answerWith { request -> AccessLedger().answer(request) }
            assertThat(get("/executions/$id", recipient).statusCode()).isEqualTo(404)
        }

        @Test
        fun `missing identity denied access and a foreign test fail before any interpreter starts`() {
            val fixture = stored()
            val other = stored()
            assertThat(post(fixture.startPath, null).statusCode()).isEqualTo(401)
            assertThat(post(fixture.startPath, "stranger").statusCode()).isEqualTo(404)
            assertThat(
                post("/snippets/${fixture.snippetId}/tests/${other.testId}/executions", fixture.actor).statusCode(),
            ).isEqualTo(404)
            assertThat(executor.requests).isEmpty()
        }

        @Test
        fun `start bodies cannot select code actors owners or other sessions`() {
            val fixture = stored()
            for (body in listOf(
                "{\"code\":\"println(2);\"}",
                "{\"actorId\":\"someone\"}",
                "{\"owner\":\"other\"}",
                "{} {}",
                "[]",
            )) {
                assertThat(post(fixture.startPath, fixture.actor, body).statusCode()).isEqualTo(400)
            }
            assertThat(executor.requests).isEmpty()
        }

        @Test
        fun `active and uncertain starts keep one actor reserved and do not repeat execution`() {
            executor.startFailure.set(ExecutionResult.Failed("execution-service-unavailable", uncertain = true))
            val fixture = stored()
            assertThat(post(fixture.startPath, fixture.actor).statusCode()).isEqualTo(503)
            assertThat(post(fixture.startPath, fixture.actor).statusCode()).isEqualTo(409)
            assertThat(executor.requests).hasSize(1)
            val row = audit.reserved().single { it.actorId == fixture.actor }
            assertThat(row.failure).isEqualTo("execution-service-unavailable")
            assertThat(row.remote).isNull()
            assertThat(row.completed).isNull()
        }

        @Test
        fun `reconnection errors keep their HTTP status before committing an NDJSON response`() {
            val fixture = stored()
            val started = post(fixture.startPath, fixture.actor)
            val id = json.readTree(started.body()).path("executionId").asString()
            executor.openFailure.set("event-history-unavailable")
            val lost = get("/executions/$id/events?after=0", fixture.actor)
            assertThat(lost.statusCode()).isEqualTo(409)
            assertThat(lost.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json")
            assertThat(json.readTree(lost.body()).path("type").asString()).endsWith("event-history-unavailable")
            assertThat(get("/executions/$id/events?after=999", fixture.actor).statusCode()).isEqualTo(400)
        }

        @Test
        fun `202 cancellation is only a request and a second consumer is rejected until the terminal`() {
            executor.script.set(ExecutionScript(outputs = listOf("early"), release = CountDownLatch(1)))
            val fixture = stored(expected = listOf("early"))
            val id = json.readTree(post(fixture.startPath, fixture.actor).body()).path("executionId").asString()
            val response =
                http.send(
                    request("/executions/$id/events?after=0", fixture.actor).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
            response.body().bufferedReader().use { input ->
                input.readLine()
                input.readLine()
                assertThat(get("/executions/$id/events?after=0", fixture.actor).statusCode()).isEqualTo(409)
                val cancel = post("/executions/$id/cancel", fixture.actor)
                assertThat(cancel.statusCode()).isEqualTo(202)
                assertThat(json.readTree(cancel.body()).path("state").asString()).isEqualTo("CANCELLING")
                assertThat(audit.find(UUID.fromString(id))?.completed).isNull()
                executor.script
                    .get()
                    .release
                    .countDown()
                val terminal = json.readTree(input.readLine()).path("data")
                assertThat(terminal.path("outcome").asString()).isEqualTo("CANCELLED")
                assertThat(terminal.has("comparison")).isFalse()
            }
        }

        private fun stored(expected: List<String> = listOf("", "ñ", "ñ")): Fixture {
            val id = UUID.randomUUID()
            val actor = "actor-${UUID.randomUUID()}"
            val reference = (storage.store("println(1);") as StoreResult.Stored).reference
            jdbc.update(
                "INSERT INTO snippets (id, name, language, version, state, creation_key, creation_actor_id, " +
                    "creation_fingerprint, content_reference) " +
                    "VALUES (?, 'run', 'printscript', '1.1', 'CONFIRMED', ?, ?, 'f', ?)",
                id,
                UUID.randomUUID(),
                actor,
                reference.value,
            )
            val test =
                definitions.create(
                    id,
                    TestCaseDefinition(listOf("", "ñ", "ñ"), expected),
                    50,
                ) as TestCaseCreation.Created
            ledger.own(actor, id)
            return Fixture(id, test.testCase.id, actor)
        }

        private fun request(
            path: String,
            actor: String?,
        ): HttpRequest.Builder {
            val builder =
                HttpRequest
                    .newBuilder(
                        URI("http://127.0.0.1:$port$path"),
                    ).header("Content-Type", "application/json")
            if (actor != null) builder.header("X-Dev-Actor-Id", actor)
            return builder
        }

        private fun post(
            path: String,
            actor: String?,
            body: String = "{}",
        ) = http.send(
            request(path, actor)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        private fun get(
            path: String,
            actor: String,
        ) = http.send(request(path, actor).GET().build(), HttpResponse.BodyHandlers.ofString())

        private data class Fixture(
            val snippetId: UUID,
            val testId: UUID,
            val actor: String,
        ) {
            val startPath: String get() = "/snippets/$snippetId/tests/$testId/executions"
        }

        companion object {
            private val ledger = AccessLedger()
            private val permissions = ScriptedHttpService(ledger::answer)

            @JvmStatic @DynamicPropertySource
            fun properties(registry: DynamicPropertyRegistry) {
                registry.add("permissions.base-url") { permissions.baseUrl }
                registry.add("storage.local.directory") { Files.createTempDirectory("test-execution-http").toString() }
            }

            @JvmStatic @AfterAll
            fun stop() {
                permissions.close()
            }
        }
    }
