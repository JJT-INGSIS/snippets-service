package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StoreResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.util.UUID

private val hello = UUID.fromString("0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40")
private val sum = UUID.fromString("7d2e9a4f-1b3c-4e5d-8f6a-0b1c2d3e4f50")
private const val UNICODE_CODE = "let saludo: string = \"¡Hola, señor! 🚀\";\nprintln(saludo);\r\n"

private data class Row(
    val name: String,
    val description: String?,
    val version: String,
    val state: String = "CONFIRMED",
)

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["permissions.read-timeout=500ms"],
)
@ActiveProfiles("dev")
@Import(MetadataTestConfiguration::class)
class DetailHttpTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") port: Int,
        private val jdbc: JdbcTemplate,
        private val storage: SnippetContentStorage,
    ) {
        private val api = SnippetsApi(port)

        @BeforeEach
        fun restorePermissions() {
            permissions.reset()
        }

        @Test
        fun `the documented detail examples are the real responses`() {
            val helloCode = stored(hello, Row("Hello", null, "1.0"), "println(\"Hello\");")
            stored(sum, Row("Sum", "Adds two numbers", "1.1"), "let a: number = 1;\nprintln(a + 2);")
            ledger.own(ACTOR, hello)
            ledger.share("dev-thiago", sum, ACTOR)

            listOf("Snippet propio", "Snippet compartido", "Sin acceso").forEach { title -> assertDocumented(title) }
            storage.delete(helloCode)
            assertDocumented("Storage no disponible")
        }

        @Test
        fun `owner and recipient read exactly the stored unicode code`() {
            val (owner, recipient) = actors()
            val id = UUID.randomUUID()
            stored(id, Row("Greeting", "Unicode", "1.1"), UNICODE_CODE)
            ledger.own(owner, id)
            ledger.share(owner, id, recipient)

            val forOwner = api.detail(id.toString(), listOf(owner))
            val forRecipient = api.detail(id.toString(), listOf(recipient))

            assertThat(forOwner.body.get("code").asString()).isEqualTo(UNICODE_CODE)
            assertThat(forRecipient.body.get("code").asString()).isEqualTo(UNICODE_CODE)
            assertThat(canModify(forOwner)).isTrue()
            assertThat(canModify(forRecipient)).isFalse()
            assertThat(forRecipient.body.get("relation").asString()).isEqualTo("shared")
        }

        @Test
        fun `an actor without access never reaches the storage`() {
            val (owner, stranger) = actors()
            val id = UUID.randomUUID()
            val code = stored(id, Row("Private", null, "1.0"), "println(1);")
            ledger.own(owner, id)
            storage.delete(code)

            assertThat(api.detail(id.toString(), listOf(stranger)).status).isEqualTo(404)
            assertThat(api.detail(id.toString(), listOf(owner)).status).isEqualTo(503)
        }

        @Test
        fun `a pending snippet is not found and permissions are not asked`() {
            val (owner) = actors()
            val id = UUID.randomUUID()
            stored(id, Row(name = "Draft", description = null, version = "1.0", state = "PENDING"), "println(1);")
            ledger.own(owner, id)

            val answer = api.detail(id.toString(), listOf(owner))

            assertThat(answer.status).isEqualTo(404)
            assertThat(answer.type).isEqualTo("urn:snippets:problem:snippet-not-found")
            assertThat(permissions.received).isEmpty()
        }

        @Test
        fun `permissions failures are failures and never a detail`() {
            val (owner) = actors()
            val id = UUID.randomUUID()
            stored(id, Row("Any", null, "1.0"), "println(1);")
            listOf(StubResponse(500, "{}"), StubResponse(200, "{}")).forEach { failure ->
                permissions.answerWith { failure }

                val answer = api.detail(id.toString(), listOf(owner))

                assertThat(answer.status).isEqualTo(502)
                assertThat(answer.type).isEqualTo("urn:snippets:problem:dependency-failure")
                assertThat(answer.body.has("code")).isFalse()
            }
        }

        @Test
        fun `invalid ids missing identity and missing content are explicit errors`() {
            val invalid = api.detail("not-a-uuid")
            assertThat(invalid.status).isEqualTo(400)
            assertThat(invalid.body.get("field").asString()).isEqualTo("snippetId")
            assertThat(api.detail(UUID.randomUUID().toString(), actors = emptyList()).status).isEqualTo(401)

            val (owner) = actors()
            val id = UUID.randomUUID()
            insert(id, Row("Broken", null, "1.0"), null)
            ledger.own(owner, id)
            assertThat(api.detail(id.toString(), listOf(owner)).status).isEqualTo(500)
        }

        private fun assertDocumented(title: String) {
            val example = readingContract.example(title)
            val lines = example.request.lines()
            val id = lines.first().removePrefix("GET /snippets/")
            val actor = lines.drop(1).single().removePrefix("X-Dev-Actor-Id: ")

            val answer = api.detail(id, listOf(actor))

            assertThat(answer.status).describedAs(title).isEqualTo(example.status)
            assertThat(answer.body).describedAs(title).isEqualTo(json.readTree(example.response))
        }

        private fun canModify(answer: HttpAnswer): Boolean {
            val capabilities = answer.body.get("capabilities")

            return capabilities.get("modify").asBoolean()
        }

        private fun actors(): List<String> = List(2) { "actor-${UUID.randomUUID()}" }

        private fun stored(
            id: UUID,
            row: Row,
            code: String,
        ): ContentReference {
            val reference = (storage.store(code) as StoreResult.Stored).reference
            insert(id, row, reference)
            return reference
        }

        private fun insert(
            id: UUID,
            row: Row,
            reference: ContentReference?,
        ) {
            jdbc.update(
                "INSERT INTO snippets (id, name, description, language, version, state, creation_key, " +
                    "creation_actor_id, creation_fingerprint, content_reference) " +
                    "VALUES (?, ?, ?, 'printscript', ?, ?, ?, 'someone-else', 'f', ?)",
                id,
                row.name,
                row.description,
                row.version,
                row.state,
                UUID.randomUUID(),
                reference?.value,
            )
        }

        companion object {
            private val ledger = AccessLedger()
            private val permissions = ScriptedHttpService { request -> ledger.answer(request) }

            @JvmStatic
            @DynamicPropertySource
            fun dependencies(registry: DynamicPropertyRegistry) {
                registry.add("permissions.base-url") { permissions.baseUrl }
                registry.add("storage.local.directory") { Files.createTempDirectory("detail-http").toString() }
            }

            @JvmStatic
            @AfterAll
            fun stopDependencies() {
                permissions.close()
            }
        }
    }
