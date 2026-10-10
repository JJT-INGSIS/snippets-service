package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.metadata.CreationLookup
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.metadata.SnippetState
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
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

private const val DOCUMENTED_ID = "550e8400-e29b-41d4-a716-446655440000"

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["permissions.read-timeout=500ms"],
)
@ActiveProfiles("dev")
@Import(MetadataTestConfiguration::class)
class CreationHttpTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") port: Int,
        private val metadata: SnippetMetadataStore,
        private val links: SnippetContentLinks,
        private val storage: SnippetContentStorage,
    ) {
        private val api = SnippetsApi(port)

        @BeforeEach
        fun restoreDependencies() {
            printScript.reset()
            permissions.reset()
        }

        @Test
        fun `the documented creation leaves code, metadata and owner under one UUID`() {
            val example = CreationContract.example("Alta válida")

            val answer = api.create(example.request)

            assertThat(answer.status).isEqualTo(example.status)
            assertThat(answer.contentType).isEqualTo("application/json")
            assertThat(withDocumentedId(answer.body)).isEqualTo(json.readTree(example.response))
            assertThat(metadata.findConfirmed(answer.id)?.state).isEqualTo(SnippetState.CONFIRMED)
            assertThat(storedCode(answer.id)).isEqualTo("println(1);")
            assertThat(owners.ownerOf(answer.id)).isEqualTo(ACTOR)
        }

        @Test
        fun `the documented retry answers the snippet that already exists`() {
            val example = CreationContract.example("Reintento del mismo pedido")
            val key = newKey()
            val created = api.create(example.request, keys = listOf(key))

            val retried = api.create(example.request, keys = listOf(key))

            assertThat(retried.status).isEqualTo(example.status)
            assertThat(retried.body).isEqualTo(created.body)
            assertThat(withDocumentedId(retried.body)).isEqualTo(json.readTree(example.response))
            assertThat(registrations()).isEqualTo(1)
        }

        @Test
        fun `the documented conflict rejects a key reused by another request or another actor`() {
            val example = CreationContract.example("Misma clave con otro pedido")
            val key = newKey()
            api.create(CreationContract.example("Alta válida").request, keys = listOf(key))

            val otherRequest = api.create(example.request, keys = listOf(key))
            val otherActor =
                api.create(CreationContract.example("Alta válida").request, keys = listOf(key), actors = listOf("x"))

            assertThat(otherRequest.status).isEqualTo(example.status)
            assertThat(otherRequest.contentType).isEqualTo("application/problem+json")
            assertThat(otherRequest.body).isEqualTo(json.readTree(example.response))
            assertThat(otherActor.body).isEqualTo(otherRequest.body)
        }

        @Test
        fun `the documented invalid code is rejected with rule, message, line and column`() {
            val example = CreationContract.example("Código inválido")
            val diagnostics = json.readTree(example.response).get("diagnostics")
            val key = newKey()
            printScript.answerWith { StubResponse(200, """{"valid":false,"diagnostics":$diagnostics}""") }

            val answer = api.create(example.request, keys = listOf(key))

            assertThat(answer.status).isEqualTo(example.status)
            assertThat(answer.body).isEqualTo(json.readTree(example.response))
            assertThat(metadata.findCreation(UUID.fromString(key), ACTOR)).isEqualTo(CreationLookup.Missing)
            assertThat(permissions.received).isEmpty()
        }

        @Test
        fun `the documented field outside the contract cannot choose the owner`() {
            val example = CreationContract.example("Campo no admitido")

            val answer = api.create(example.request)

            assertThat(answer.status).isEqualTo(example.status)
            assertThat(answer.body).isEqualTo(json.readTree(example.response))
            assertThat(permissions.received).isEmpty()
        }

        @Test
        fun `the documented request without identity is rejected`() {
            val example = CreationContract.example("Pedido sin identidad")

            val answer = api.create(example.request, actors = emptyList())
            val twoIdentities = api.create(example.request, actors = listOf(ACTOR, "dev-thiago"))

            assertThat(answer.status).isEqualTo(example.status)
            assertThat(answer.body).isEqualTo(json.readTree(example.response))
            assertThat(twoIdentities.body).isEqualTo(answer.body)
            assertThat(printScript.received).isEmpty()
        }

        @Test
        fun `every documented example is one of the tests above`() {
            assertThat(CreationContract.examples.map { example -> example.title }).containsExactly(
                "Alta válida",
                "Reintento del mismo pedido",
                "Misma clave con otro pedido",
                "Código inválido",
                "Campo no admitido",
                "Pedido sin identidad",
            )
        }

        @Test
        fun `requests that cannot be read are invalid before anything is reserved`() {
            val valid = requestBody()
            val withoutKey = api.create(valid, keys = emptyList())

            assertThat(api.create("{ not json").type).isEqualTo("urn:snippets:problem:invalid-request")
            assertThat(api.create("").status).isEqualTo(400)
            assertThat(api.create(requestBody(name = " ")).status).isEqualTo(400)
            assertThat(withoutKey.status).isEqualTo(400)
            assertThat(withoutKey.body.get("field").asString()).isEqualTo("Idempotency-Key")
            assertThat(api.create(valid, keys = listOf("1-1-1-1-1")).status).isEqualTo(400)
            assertThat(api.create(valid, keys = listOf(newKey(), newKey())).status).isEqualTo(400)
            assertThat(api.create(valid, contentType = "text/plain").status).isEqualTo(415)
            assertThat(printScript.received).isEmpty()
            assertThat(permissions.received).isEmpty()
        }

        @Test
        fun `unsupported languages and versions are rejected without reserving`() {
            val key = newKey()
            printScript.answerWith {
                StubResponse(422, """{"status":422,"supportedVersions":["1.0","1.1"]}""", "application/problem+json")
            }

            val language = api.create(requestBody(language = "python"), keys = listOf(key))
            val version = api.create(requestBody(version = "9.9"), keys = listOf(key))

            assertThat(language.status).isEqualTo(422)
            assertThat(language.type).isEqualTo("urn:snippets:problem:unsupported-language")
            assertThat(version.status).isEqualTo(422)
            assertThat(version.type).isEqualTo("urn:snippets:problem:unsupported-version")
            assertThat(metadata.findCreation(UUID.fromString(key), ACTOR)).isEqualTo(CreationLookup.Missing)
        }

        @Test
        fun `code from a file is stored exactly as it was received`() {
            val fileContent = "\uFEFFlet saludo: string = \"¡Hola, 🌍!\";\r\n\tprintln(saludo);\r\n\r\n"

            val answer = api.create(requestBody(code = fileContent))

            assertThat(answer.status).isEqualTo(201)
            assertThat(storedCode(answer.id)).isEqualTo(fileContent)
        }

        @Test
        fun `a language service failure is not a verdict about the code`() {
            val key = newKey()
            printScript.answerWith { StubResponse(500, "{}") }

            val answer = api.create(requestBody(), keys = listOf(key))

            assertThat(answer.status).isEqualTo(502)
            assertThat(answer.type).isEqualTo("urn:snippets:problem:dependency-failure")
            assertThat(metadata.findCreation(UUID.fromString(key), ACTOR)).isEqualTo(CreationLookup.Missing)
        }

        @Test
        fun `a Permissions timeout is not a success and the same key completes the creation`() {
            val key = newKey()
            permissions.answerWith { request -> owners.register(request).copy(delay = Duration.ofSeconds(2)) }

            val timedOut = api.create(requestBody(), keys = listOf(key))

            assertThat(timedOut.status).isEqualTo(502)
            assertThat(timedOut.type).isEqualTo("urn:snippets:problem:dependency-failure")
            val pending = pending(key)
            assertThat(metadata.findConfirmed(pending.id)).isNull()
            assertThat(owners.ownerOf(pending.id)).isEqualTo(ACTOR)

            permissions.reset()
            val retried = api.create(requestBody(), keys = listOf(key))

            assertThat(retried.status).isEqualTo(201)
            assertThat(retried.id).isEqualTo(pending.id)
            assertThat(storedCode(pending.id)).isEqualTo("println(1);")
        }

        @Test
        fun `a snippet that Permissions assigns to someone else is never confirmed`() {
            val key = newKey()
            permissions.answerWith { request -> ownerConflict(request.path) }

            val answer = api.create(requestBody(), keys = listOf(key))

            assertThat(answer.status).isEqualTo(409)
            assertThat(answer.type).isEqualTo("urn:snippets:problem:ownership-conflict")
            assertThat(metadata.findConfirmed(pending(key).id)).isNull()
        }

        @Test
        fun `a storage failure is not a success and the same key completes the creation`() {
            val key = newKey()
            val away = contentDirectory.resolveSibling("${contentDirectory.fileName}-away")
            Files.move(contentDirectory, away)

            val failed = api.create(requestBody(), keys = listOf(key))
            Files.move(away, contentDirectory)

            assertThat(failed.status).isEqualTo(503)
            assertThat(failed.type).isEqualTo("urn:snippets:problem:storage-unavailable")
            val pending = pending(key)
            assertThat(metadata.findConfirmed(pending.id)).isNull()
            assertThat(permissions.received).isEmpty()

            val retried = api.create(requestBody(), keys = listOf(key))

            assertThat(retried.status).isEqualTo(201)
            assertThat(retried.id).isEqualTo(pending.id)
            assertThat(owners.ownerOf(pending.id)).isEqualTo(ACTOR)
        }

        private fun newKey(): String = UUID.randomUUID().toString()

        private fun pending(key: String): SnippetMetadata {
            val found = metadata.findCreation(UUID.fromString(key), ACTOR) as CreationLookup.Found
            assertThat(found.metadata.state).isEqualTo(SnippetState.PENDING)

            return found.metadata
        }

        private fun storedCode(snippetId: UUID): String? {
            val reference = links.findContent(snippetId) ?: return null

            return (storage.read(reference) as? ReadResult.Found)?.code
        }

        private fun registrations(): Int = permissions.received.count { request -> request.method == "PUT" }

        private fun withDocumentedId(body: JsonNode): JsonNode {
            val copy = body.deepCopy() as ObjectNode

            return copy.put("id", DOCUMENTED_ID)
        }

        companion object {
            private val owners = OwnershipLedger()
            private val printScript = ScriptedHttpService { acceptedCode }
            private val permissions = ScriptedHttpService { request -> owners.register(request) }
            private val contentDirectory: Path = Files.createTempDirectory("creation-http")

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
