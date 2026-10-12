package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.metadata.CreationLookup
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

private const val DOCUMENTED_ID = "550e8400-e29b-41d4-a716-446655440000"
private const val ORIGINAL_CODE = "println(1);"

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["permissions.read-timeout=500ms"],
)
@ActiveProfiles("dev")
@Import(MetadataTestConfiguration::class)
class UpdateHttpTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") port: Int,
        private val metadata: SnippetMetadataStore,
        private val links: SnippetContentLinks,
        private val storage: SnippetContentStorage,
    ) {
        private val api = SnippetsApi(port)
        private val replacement = updateContract.example("Reemplazo completo")

        @BeforeEach
        fun restoreDependencies() {
            printScript.reset()
            permissions.reset()
        }

        @Test
        fun `the documented replacement changes the code and every detail of a created snippet`() {
            val created = created()

            val answer = api.update(created.id.toString(), replacement.request)

            assertThat(answer.status).isEqualTo(replacement.status)
            assertThat(answer.contentType).isEqualTo("application/json")
            assertThat(documented(answer, created.id)).isEqualTo(json.readTree(replacement.response))
            assertThat(metadata.findConfirmed(created.id)?.name).isEqualTo("Renamed")
            assertThat(metadata.findConfirmed(created.id)?.version).isEqualTo("1.1")
            assertThat(storedCode(created.id)).isEqualTo("println(2);")
        }

        @Test
        fun `the documented absent description removes the one the snippet had`() {
            val example = updateContract.example("Descripción ausente")
            val created = created()
            val described = requestBody().replace("\"description\":null", "\"description\":\"Draft\"")
            api.update(created.id.toString(), described)
            val before = metadata.findConfirmed(created.id)?.description

            val answer = api.update(created.id.toString(), example.request)

            assertThat(before).isEqualTo("Draft")
            assertThat(answer.status).isEqualTo(example.status)
            assertThat(documented(answer, created.id)).isEqualTo(json.readTree(example.response))
            assertThat(metadata.findConfirmed(created.id)?.description).isNull()
        }

        @Test
        fun `the documented missing field is rejected instead of keeping the current code`() {
            val example = updateContract.example("Campo obligatorio ausente")
            val created = created()

            val answer = api.update(created.id.toString(), example.request)

            assertThat(answer.status).isEqualTo(example.status)
            assertThat(documented(answer, created.id)).isEqualTo(json.readTree(example.response))
            assertUnchanged(created)
        }

        @Test
        fun `the documented actor who is not the owner cannot modify the snippet`() {
            val example = updateContract.example("Actor que no es el owner")
            val created = created()

            val answer = api.update(created.id.toString(), example.request, actors = listOf("dev-thiago"))

            assertThat(answer.status).isEqualTo(example.status)
            assertThat(answer.contentType).isEqualTo("application/problem+json")
            assertThat(documented(answer, created.id)).isEqualTo(json.readTree(example.response))
            assertUnchanged(created)
        }

        @Test
        fun `the documented missing snippet is not found`() {
            val example = updateContract.example("Snippet inexistente")

            val answer = api.update(DOCUMENTED_ID, example.request)

            assertThat(answer.status).isEqualTo(example.status)
            assertThat(answer.body).isEqualTo(json.readTree(example.response))
            assertThat(permissions.received).isEmpty()
        }

        @Test
        fun `the documented invalid code keeps the previous code and details`() {
            val example = updateContract.example("Código inválido")
            val diagnostics = json.readTree(example.response).get("diagnostics")
            val created = created()
            printScript.answerWith { StubResponse(200, """{"valid":false,"diagnostics":$diagnostics}""") }

            val answer = api.update(created.id.toString(), example.request)

            assertThat(answer.status).isEqualTo(example.status)
            assertThat(documented(answer, created.id)).isEqualTo(json.readTree(example.response))
            assertUnchanged(created)
        }

        @Test
        fun `every documented example is one of the tests above`() {
            assertThat(updateContract.examples.map { example -> example.title }).containsExactly(
                "Reemplazo completo",
                "Descripción ausente",
                "Campo obligatorio ausente",
                "Actor que no es el owner",
                "Snippet inexistente",
                "Código inválido",
            )
        }

        @Test
        fun `requests that cannot be read change nothing`() {
            val created = created()
            val id = created.id.toString()
            val withOwner = replacement.request.replace("{", "{\"ownerId\":\"dev-thiago\",")
            val badId = api.update("1-1-1-1-1", replacement.request)

            assertThat(badId.status).isEqualTo(400)
            assertThat(badId.body.get("field").asString()).isEqualTo("snippetId")
            assertThat(api.update(id, "{ not json").type).isEqualTo("urn:snippets:problem:invalid-request")
            assertThat(api.update(id, withOwner).status).isEqualTo(400)
            assertThat(api.update(id, requestBody(name = " ")).status).isEqualTo(400)
            assertThat(api.update(id, replacement.request, actors = emptyList()).status).isEqualTo(401)
            assertThat(api.update(id, replacement.request, contentType = "text/plain").status).isEqualTo(415)
            assertUnchanged(created)
        }

        @Test
        fun `a new language or version is validated with the new code and unsupported ones change nothing`() {
            val created = created()
            printScript.answerWith {
                StubResponse(422, """{"status":422,"supportedVersions":["1.0","1.1"]}""", "application/problem+json")
            }

            val language = api.update(created.id.toString(), requestBody(language = "python"))
            val version = api.update(created.id.toString(), requestBody(version = "9.9", code = "println(9);"))

            assertThat(language.type).isEqualTo("urn:snippets:problem:unsupported-language")
            assertThat(version.type).isEqualTo("urn:snippets:problem:unsupported-version")
            assertThat(printScript.received.last().body).contains("9.9", "println(9);")
            assertUnchanged(created)
        }

        @Test
        fun `code from a file replaces the previous one exactly as it was received`() {
            val created = created()
            val fileContent = "\uFEFFlet saludo: string = \"¡Hola, 🌍!\";\r\n\tprintln(saludo);\r\n\r\n"

            val answer = api.update(created.id.toString(), requestBody(code = fileContent))

            assertThat(answer.status).isEqualTo(200)
            assertThat(storedCode(created.id)).isEqualTo(fileContent)
        }

        @Test
        fun `a Permissions failure or timeout is not a denial and changes nothing`() {
            val created = created()

            permissions.answerWith { StubResponse(500, "{}") }
            val failed = api.update(created.id.toString(), replacement.request)
            permissions.answerWith { request -> owners.answer(request).copy(delay = Duration.ofSeconds(2)) }
            val timedOut = api.update(created.id.toString(), replacement.request)

            assertThat(failed.status).isEqualTo(502)
            assertThat(failed.type).isEqualTo("urn:snippets:problem:dependency-failure")
            assertThat(timedOut.status).isEqualTo(502)
            assertThat(timedOut.type).isEqualTo("urn:snippets:problem:dependency-failure")
            assertUnchanged(created)
        }

        @Test
        fun `a language service failure is not a verdict about the code and changes nothing`() {
            val created = created()
            printScript.answerWith { StubResponse(500, "{}") }

            val answer = api.update(created.id.toString(), replacement.request)

            assertThat(answer.status).isEqualTo(502)
            assertUnchanged(created)
        }

        @Test
        fun `a storage failure changes nothing and the same request works afterwards`() {
            val created = created()
            val away = contentDirectory.resolveSibling("${contentDirectory.fileName}-away")
            Files.move(contentDirectory, away)

            val failed = api.update(created.id.toString(), replacement.request)
            Files.move(away, contentDirectory)

            assertThat(failed.status).isEqualTo(503)
            assertThat(failed.type).isEqualTo("urn:snippets:problem:storage-unavailable")
            assertUnchanged(created)
            assertThat(api.update(created.id.toString(), replacement.request).status).isEqualTo(200)
            assertThat(storedCode(created.id)).isEqualTo("println(2);")
        }

        @Test
        fun `a snippet whose creation is pending cannot be updated`() {
            val key = UUID.randomUUID().toString()
            permissions.answerWith { StubResponse(500, "{}") }
            api.create(requestBody(), keys = listOf(key))
            val pending = (metadata.findCreation(UUID.fromString(key), ACTOR) as CreationLookup.Found).metadata
            permissions.reset()

            val answer = api.update(pending.id.toString(), replacement.request)

            assertThat(answer.status).isEqualTo(409)
            assertThat(answer.type).isEqualTo("urn:snippets:problem:snippet-pending")
            assertThat(metadata.findMetadata(pending.id)).isEqualTo(pending)
            assertThat(permissions.received).isEmpty()
        }

        @Test
        fun `a confirmed snippet without a registered owner cannot be updated by anyone`() {
            val created = created()
            permissions.answerWith { request -> ownershipMissing(request.path) }

            val answer = api.update(created.id.toString(), replacement.request)

            assertThat(answer.status).isEqualTo(409)
            assertThat(answer.type).isEqualTo("urn:snippets:problem:ownership-missing")
            assertUnchanged(created)
        }

        private fun created(): SnippetMetadata {
            val answer = api.create(creationContract.example("Alta válida").request)
            assertThat(answer.status).isEqualTo(201)

            return checkNotNull(metadata.findConfirmed(answer.id))
        }

        private fun assertUnchanged(created: SnippetMetadata) {
            assertThat(metadata.findConfirmed(created.id)).isEqualTo(created)
            assertThat(storedCode(created.id)).isEqualTo(ORIGINAL_CODE)
        }

        private fun storedCode(snippetId: UUID): String? {
            val reference = links.findContent(snippetId) ?: return null

            return (storage.read(reference) as? ReadResult.Found)?.code
        }

        private fun documented(
            answer: HttpAnswer,
            snippetId: UUID,
        ): JsonNode = json.readTree(answer.body.toString().replace(snippetId.toString(), DOCUMENTED_ID))

        companion object {
            private val owners = OwnershipLedger()
            private val printScript = ScriptedHttpService { acceptedCode }
            private val permissions = ScriptedHttpService { request -> owners.answer(request) }
            private val contentDirectory: Path = Files.createTempDirectory("update-http")

            @JvmStatic
            @DynamicPropertySource
            fun dependencies(registry: DynamicPropertyRegistry) {
                registry.add("language.printscript.base-url") { printScript.baseUrl }
                registry.add("permissions.base-url") { permissions.baseUrl }
                registry.add("storage.local.directory") { contentDirectory.toString() }
            }

            @JvmStatic
            @AfterAll
            fun stopDependencies() {
                printScript.close()
                permissions.close()
            }
        }
    }
