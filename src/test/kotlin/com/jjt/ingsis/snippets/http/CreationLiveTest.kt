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
class CreationLiveTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") port: Int,
        private val metadata: SnippetMetadataStore,
        private val links: SnippetContentLinks,
        private val storage: SnippetContentStorage,
        private val ownership: OwnershipReader,
    ) {
        private val api = SnippetsApi(port)

        @Test
        fun `the real services create a snippet with its code, metadata and owner`() {
            val key = UUID.randomUUID().toString()
            val request = CreationContract.example("Alta válida").request

            val created = api.create(request, keys = listOf(key))
            val retried = api.create(request, keys = listOf(key))

            assertThat(created.status).isEqualTo(201)
            assertThat(metadata.findConfirmed(created.id)).isNotNull()
            assertThat(storedCode(created.id)).isEqualTo("println(1);")
            assertThat(ownership.find(created.id)).isEqualTo(OwnershipLookup.Found(Ownership(created.id, ACTOR)))
            assertThat(retried.status).isEqualTo(200)
            assertThat(retried.body).isEqualTo(created.body)
        }

        @Test
        fun `the real PrintScript diagnostics reach the documented response`() {
            val example = CreationContract.example("Código inválido")

            val answer = api.create(example.request)

            assertThat(answer.status).isEqualTo(example.status)
            assertThat(answer.body).isEqualTo(json.readTree(example.response))
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
                registry.add("storage.local.directory") { Files.createTempDirectory("creation-live").toString() }
            }
        }
    }
