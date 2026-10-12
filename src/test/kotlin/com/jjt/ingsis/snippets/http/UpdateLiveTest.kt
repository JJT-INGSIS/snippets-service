package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.permissions.Ownership
import com.jjt.ingsis.snippets.permissions.OwnershipLookup
import com.jjt.ingsis.snippets.permissions.OwnershipReader
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "PRINTSCRIPT_SERVICE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PERMISSIONS_SERVICE_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(MetadataTestConfiguration::class)
class UpdateLiveTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") port: Int,
        private val metadata: SnippetMetadataStore,
        private val links: SnippetContentLinks,
        private val storage: SnippetContentStorage,
        private val ownership: OwnershipReader,
    ) {
        private val api = SnippetsApi(port)
        private val creation = creationContract.example("Alta válida").request
        private val replacement = updateContract.example("Reemplazo completo").request

        @Test
        fun `the real services update a snippet created through the creation endpoint`() {
            val created = api.create(creation)

            val updated = api.update(created.id.toString(), replacement)

            assertThat(created.status).isEqualTo(201)
            assertThat(updated.status).isEqualTo(200)
            assertThat(updated.id).isEqualTo(created.id)
            assertThat(metadata.findConfirmed(created.id)?.name).isEqualTo("Renamed")
            assertThat(metadata.findConfirmed(created.id)?.version).isEqualTo("1.1")
            assertThat(storedCode(created.id)).isEqualTo("println(2);")
            assertThat(ownership.find(created.id)).isEqualTo(OwnershipLookup.Found(Ownership(created.id, ACTOR)))
        }

        @Test
        fun `the real Permissions denies an actor who is not the owner and nothing changes`() {
            val created = api.create(creation)
            val before = metadata.findConfirmed(created.id)

            val denied = api.update(created.id.toString(), replacement, actors = listOf("dev-thiago"))

            assertThat(denied.status).isEqualTo(403)
            assertThat(denied.type).isEqualTo("urn:snippets:problem:modification-denied")
            assertThat(metadata.findConfirmed(created.id)).isEqualTo(before)
            assertThat(storedCode(created.id)).isEqualTo("println(1);")
        }

        @Test
        fun `the real PrintScript rejects an invalid replacement and the previous code stays`() {
            val example = updateContract.example("Código inválido")
            val created = api.create(creation)

            val answer = api.update(created.id.toString(), example.request)

            val expected = example.response.replace("550e8400-e29b-41d4-a716-446655440000", created.id.toString())
            assertThat(answer.status).isEqualTo(example.status)
            assertThat(answer.body).isEqualTo(json.readTree(expected))
            assertThat(storedCode(created.id)).isEqualTo("println(1);")
        }

        @Test
        fun `the real PrintScript validates the new version with the new code`() {
            val onlyValidIn11 = "const ready: boolean = true;\nif (ready) {\n  println(\"listo\");\n}"
            val created = api.create(creation)

            val in10 = api.update(created.id.toString(), requestBody(code = onlyValidIn11, version = "1.0"))
            val in11 = api.update(created.id.toString(), requestBody(code = onlyValidIn11, version = "1.1"))

            assertThat(in10.status).isEqualTo(422)
            assertThat(in11.status).isEqualTo(200)
            assertThat(storedCode(created.id)).isEqualTo(onlyValidIn11)
        }

        private fun storedCode(snippetId: UUID): String? {
            val reference = links.findContent(snippetId) ?: return null

            return (storage.read(reference) as? ReadResult.Found)?.code
        }

        companion object {
            @JvmStatic
            @DynamicPropertySource
            fun realServices(registry: DynamicPropertyRegistry) {
                registry.add("language.printscript.base-url") { System.getenv("PRINTSCRIPT_SERVICE_URL") }
                registry.add("permissions.base-url") { System.getenv("PERMISSIONS_SERVICE_URL") }
                registry.add("storage.local.directory") { Files.createTempDirectory("update-live").toString() }
            }
        }
    }
