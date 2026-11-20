package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.http.AccessLedger
import com.jjt.ingsis.snippets.http.ScriptedHttpService
import com.jjt.ingsis.snippets.http.json
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StoreResult
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
import tools.jackson.databind.JsonNode
import java.io.BufferedReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["execution.recovery.delay-ms=3600000"],
)
@ActiveProfiles("dev")
@Import(MetadataTestConfiguration::class)
@EnabledIfEnvironmentVariable(named = "PRINTSCRIPT_EXECUTION_URL", matches = ".+")
@Timeout(30)
class InteractiveExecutionLiveTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") private val port: Int,
        private val jdbc: JdbcTemplate,
        private val storage: SnippetContentStorage,
        private val audit: ExecutionAudit,
    ) {
        private val http = HttpClient.newHttpClient()

        @Test
        fun `a real interpreter asks for input accepts it once and finishes without a comparison`() {
            val actor = "live-${UUID.randomUUID()}"
            val snippetId = stored(actor, "let name: string = readInput(\"Nombre?\"); println(\"Hola, \" + name);")
            val start = send(request("/snippets/$snippetId/executions", actor).POST(body("{}")))
            assertThat(start.statusCode()).describedAs(start.body()).isEqualTo(201)
            val id = json.readTree(start.body()).path("executionId").asString()
            val stream =
                http.send(
                    request("/executions/$id/events?after=0", actor).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
            assertThat(stream.statusCode()).isEqualTo(200)

            stream.body().bufferedReader().use { input ->
                val prompt = events(input).first { it.path("type").asString() == "input-required" }
                val requestId = prompt.path("data").path("requestId").asString()
                val answer = "{\"requestId\":\"$requestId\",\"value\":\"Ana\"}"
                val accepted = send(request("/executions/$id/inputs", actor).POST(body(answer)))
                assertThat(accepted.statusCode()).describedAs(accepted.body()).isEqualTo(200)
                val repeated = send(request("/executions/$id/inputs", actor).POST(body(answer)))
                assertThat(repeated.statusCode()).describedAs(repeated.body()).isEqualTo(200)

                val rest = events(input).toList()
                val types = rest.map { it.path("type").asString() }
                val outputs = rest.filter { it.path("type").asString() == "output" }
                assertThat(types).contains("input-accepted")
                assertThat(outputs.map { it.path("data").path("text").asString() }).containsExactly("Hola, Ana")
                assertThat(
                    rest
                        .last()
                        .path("data")
                        .path("outcome")
                        .asString(),
                ).isEqualTo("COMPLETED")
                assertThat(rest.last().path("data").has("comparison")).isFalse()
            }
            val saved = checkNotNull(audit.find(UUID.fromString(id)))
            assertThat(saved.snapshot.test).isNull()
            assertThat(saved.completed?.outputs).isEmpty()
            assertThat(saved.reserved).isFalse()
        }

        private fun stored(
            actor: String,
            code: String,
        ): UUID {
            val snippetId = UUID.randomUUID()
            val reference = (storage.store(code) as StoreResult.Stored).reference
            jdbc.update(
                "INSERT INTO snippets (id, name, language, version, state, creation_key, creation_actor_id, " +
                    "creation_fingerprint, content_reference) " +
                    "VALUES (?, 'live', 'printscript', '1.1', 'CONFIRMED', ?, ?, 'f', ?)",
                snippetId,
                UUID.randomUUID(),
                actor,
                reference.value,
            )
            ledger.own(actor, snippetId)
            return snippetId
        }

        private fun events(input: BufferedReader): Sequence<JsonNode> =
            generateSequence { input.readLine() }.map(json::readTree)

        private fun body(text: String): HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.ofString(text)

        private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())

        private fun request(
            path: String,
            actor: String,
        ): HttpRequest.Builder =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Dev-Actor-Id", actor)

        companion object {
            private val ledger = AccessLedger()
            private val permissions = ScriptedHttpService(ledger::answer)

            @JvmStatic @DynamicPropertySource
            fun properties(registry: DynamicPropertyRegistry) {
                registry.add("permissions.base-url") { permissions.baseUrl }
                registry.add("storage.local.directory") {
                    Files.createTempDirectory("interactive-execution-live").toString()
                }
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
