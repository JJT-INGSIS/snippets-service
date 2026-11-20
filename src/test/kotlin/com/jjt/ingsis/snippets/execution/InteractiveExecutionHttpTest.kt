package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.http.AccessLedger
import com.jjt.ingsis.snippets.http.ScriptedHttpService
import com.jjt.ingsis.snippets.http.json
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.execution.ExecutionMode
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
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

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["execution.recovery.delay-ms=3600000"],
)
@ActiveProfiles("dev")
@MockitoBean(types = [Language::class])
@Import(MetadataTestConfiguration::class)
class InteractiveExecutionHttpTest
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
        fun `an interactive start describes a session without test fields and streams it without keeping output`() {
            val fixture = stored()
            val started = post(fixture.interactivePath, fixture.actor)

            assertThat(started.statusCode()).isEqualTo(201)
            val descriptor = json.readTree(started.body())
            val id = descriptor.path("executionId").asString()
            assertThat(started.headers().firstValue("Location").orElse("")).endsWith("/executions/$id")
            assertThat(descriptor.path("mode").asString()).isEqualTo("INTERACTIVE")
            assertThat(descriptor.has("testId")).isFalse()
            assertThat(descriptor.path("snapshot").has("testHash")).isFalse()
            assertThat(descriptor.path("snapshot").path("codeHash").asString()).startsWith("sha256-v1:")
            assertThat(executor.requests.single().mode).isEqualTo(ExecutionMode.INTERACTIVE)
            assertThat(executor.requests.single().inputs).isEmpty()

            val events =
                get("/executions/$id/events?after=0", fixture.actor)
                    .body()
                    .trim()
                    .lines()
                    .map(json::readTree)
            assertThat(
                events
                    .first()
                    .path("data")
                    .path("mode")
                    .asString(),
            ).isEqualTo("INTERACTIVE")
            assertThat(events.last().path("data").has("comparison")).isFalse()
            val saved = checkNotNull(audit.find(UUID.fromString(id)))
            assertThat(saved.completed?.outputs).isEmpty()
            assertThat(saved.reserved).isFalse()
        }

        @Test
        fun `an input reaches the interpreter and the answer follows the contract`() {
            val fixture = stored()
            val id = startedId(fixture.interactivePath, fixture.actor)
            val requestId = UUID.randomUUID()
            executor.inputResult.set(ExecutionResult.Success(Unit))

            val accepted = post("/executions/$id/inputs", fixture.actor, inputBody(requestId, "Ana"))
            assertThat(accepted.statusCode()).isEqualTo(200)
            assertThat(accepted.headers().firstValue("Content-Type").orElse("")).startsWith("application/json")
            assertThat(json.readTree(accepted.body()).path("requestId").asString()).isEqualTo(requestId.toString())
            assertThat(json.readTree(accepted.body()).path("accepted").asBoolean()).isTrue()
            assertThat(executor.inputs.single().value).isEqualTo("Ana")

            executor.inputResult.set(ExecutionResult.Rejected("input-conflict"))
            val conflict = post("/executions/$id/inputs", fixture.actor, inputBody(requestId, "Otra"))
            assertThat(conflict.statusCode()).isEqualTo(409)
            assertThat(json.readTree(conflict.body()).path("type").asString())
                .isEqualTo("urn:snippets:problem:input-conflict")
            val tooLarge = post("/executions/$id/inputs", fixture.actor, inputBody(requestId, "ñ".repeat(4097)))
            assertThat(tooLarge.statusCode()).isEqualTo(413)
        }

        @Test
        fun `invalid bodies and test sessions never reach the interpreter`() {
            val fixture = stored()
            for (body in listOf("{\"code\":\"println(2);\"}", "{} {}", "[]")) {
                assertThat(post(fixture.interactivePath, fixture.actor, body).statusCode()).isEqualTo(400)
            }
            assertThat(executor.requests).isEmpty()
            val id = startedId(fixture.interactivePath, fixture.actor)
            val requestId = UUID.randomUUID()
            for (body in listOf(
                "{}",
                "{\"requestId\":\"$requestId\"}",
                "{\"requestId\":\"not-a-uuid\",\"value\":\"a\"}",
                "{\"requestId\":\"$requestId\",\"value\":1}",
                "{\"requestId\":\"$requestId\",\"value\":\"a\",\"actorId\":\"x\"}",
                "{\"requestId\":\"$requestId\",\"value\":\"a\",\"value\":\"b\"}",
            )) {
                assertThat(post("/executions/$id/inputs", fixture.actor, body).statusCode()).isEqualTo(400)
            }
            val other = stored()
            val testRun = startedId(other.testPath, other.actor)
            assertThat(post("/executions/$testRun/inputs", other.actor, inputBody(requestId, "a")).statusCode())
                .isEqualTo(409)
            assertThat(executor.inputs).isEmpty()
        }

        @Test
        fun `starting needs read access and only the actor who started may send input`() {
            val fixture = stored()
            val recipient = "recipient-${UUID.randomUUID()}"
            ledger.share(fixture.actor, fixture.snippetId, recipient)
            assertThat(post(fixture.interactivePath, null).statusCode()).isEqualTo(401)
            assertThat(post(fixture.interactivePath, "stranger-${UUID.randomUUID()}").statusCode()).isEqualTo(404)
            assertThat(executor.requests).isEmpty()

            val id = startedId(fixture.interactivePath, recipient)
            assertThat(post(fixture.interactivePath, recipient).statusCode()).isEqualTo(409)
            val input = inputBody(UUID.randomUUID(), "Ana")
            assertThat(post("/executions/$id/inputs", fixture.actor, input).statusCode()).isEqualTo(403)
            assertThat(executor.inputs).isEmpty()
        }

        private fun stored(): Fixture {
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
                    TestCaseDefinition(emptyList(), emptyList()),
                    50,
                ) as TestCaseCreation.Created
            ledger.own(actor, id)
            return Fixture(id, test.testCase.id, actor)
        }

        private fun startedId(
            path: String,
            actor: String,
        ): String {
            val started = post(path, actor)
            assertThat(started.statusCode()).describedAs(started.body()).isEqualTo(201)
            return json.readTree(started.body()).path("executionId").asString()
        }

        private fun inputBody(
            requestId: UUID,
            value: String,
        ): String = json.writeValueAsString(mapOf("requestId" to requestId.toString(), "value" to value))

        private fun request(
            path: String,
            actor: String?,
        ): HttpRequest.Builder {
            val builder =
                HttpRequest
                    .newBuilder(URI("http://127.0.0.1:$port$path"))
                    .header("Content-Type", "application/json")
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
            val interactivePath: String get() = "/snippets/$snippetId/executions"
            val testPath: String get() = "/snippets/$snippetId/tests/$testId/executions"
        }

        companion object {
            private val ledger = AccessLedger()
            private val permissions = ScriptedHttpService(ledger::answer)

            @JvmStatic @DynamicPropertySource
            fun properties(registry: DynamicPropertyRegistry) {
                registry.add("permissions.base-url") { permissions.baseUrl }
                registry.add("storage.local.directory") {
                    Files.createTempDirectory("interactive-execution-http").toString()
                }
            }

            @JvmStatic @AfterAll
            fun stop() {
                permissions.close()
            }
        }
    }
