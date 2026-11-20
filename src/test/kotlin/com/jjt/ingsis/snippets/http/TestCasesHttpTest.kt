package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
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

private val documentedSnippet = UUID.fromString("0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40")
private val documentedTestCase = UUID.fromString("5b6c7d8e-9f01-4a2b-8c3d-4e5f60718293")
private val untestedSnippet = UUID.fromString("7d2e9a4f-1b3c-4e5d-8f6a-0b1c2d3e4f50")
private const val TRICKY_BODY =
    "{\"inputs\":[\"\",\"ñandú\",\"ñandú\",\"🚀 e\u0301\"],\"expectedOutputs\":[\"línea\\nsiguiente\",\"\",\"  \"]}"

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["permissions.read-timeout=500ms"],
)
@ActiveProfiles("dev")
@Import(MetadataTestConfiguration::class)
class TestCasesHttpTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") port: Int,
        private val jdbc: JdbcTemplate,
    ) {
        private val api = SnippetsApi(port)

        @BeforeEach
        fun restorePermissions() {
            permissions.reset()
        }

        @Test
        fun `the documented examples are the real responses`() {
            insertSnippet(documentedSnippet)
            insertSnippet(untestedSnippet)
            ledger.own(ACTOR, documentedSnippet)
            ledger.share(ACTOR, documentedSnippet, "dev-thiago")
            ledger.own(ACTOR, untestedSnippet)
            insertTestCase(documentedSnippet, documentedTestCase)

            listOf(
                "Tests de un snippet compartido",
                "Snippet sin tests",
                "Un usuario compartido intenta crear",
                "Sin acceso",
                "Body inválido",
            ).forEach { title -> assertDocumented(title) }
            assertDocumentedCreation()
        }

        @Test
        fun `the owner gets back exactly what was created and the recipient lists it`() {
            val (owner, recipient) = actors()
            val id = ownedSnippet(owner)
            ledger.share(owner, id, recipient)

            val created = api.createTestCase(id.toString(), TRICKY_BODY, listOf(owner))
            val listed = api.testCases(id.toString(), listOf(recipient))

            assertThat(created.status).isEqualTo(201)
            assertThat(created.body.get("snippetId").asString()).isEqualTo(id.toString())
            assertThat(listed.status).isEqualTo(200)
            assertThat(listed.body.get("items").single()).isEqualTo(created.body)
            assertThat(withoutIds(created)).isEqualTo(json.readTree(TRICKY_BODY))
        }

        @Test
        fun `only the owner creates and a stranger sees nothing`() {
            val (owner, recipient) = actors()
            val stranger = "actor-${UUID.randomUUID()}"
            val id = ownedSnippet(owner)
            ledger.share(owner, id, recipient)

            val denied = api.createTestCase(id.toString(), TRICKY_BODY, listOf(recipient))

            assertThat(denied.status).isEqualTo(403)
            assertThat(denied.type).isEqualTo("urn:snippets:problem:access-denied")
            assertThat(api.createTestCase(id.toString(), TRICKY_BODY, listOf(stranger)).status).isEqualTo(404)
            assertThat(api.testCases(id.toString(), listOf(stranger)).status).isEqualTo(404)
            assertThat(
                api
                    .testCases(id.toString(), listOf(owner))
                    .body
                    .get("items")
                    .isEmpty,
            ).isTrue()
        }

        @Test
        fun `a pending snippet or an invalid body never reach permissions`() {
            val (owner) = actors()
            val pending = UUID.randomUUID()
            insertSnippet(pending, "PENDING")
            ledger.own(owner, pending)

            assertThat(api.testCases(pending.toString(), listOf(owner)).status).isEqualTo(404)
            assertThat(api.createTestCase(pending.toString(), TRICKY_BODY, listOf(owner)).status).isEqualTo(404)
            listOf("{\"inputs\":[1],\"expectedOutputs\":[]}", "{\"inputs\":[]}", "").forEach { body ->
                assertThat(api.createTestCase(pending.toString(), body, listOf(owner)).status).isEqualTo(400)
            }
            assertThat(permissions.received).isEmpty()
        }

        @Test
        fun `size limits answer 413 and name the limit`() {
            val (owner) = actors()
            val id = ownedSnippet(owner)
            val tooMany =
                json.writeValueAsString(
                    mapOf(
                        "inputs" to List(101) { "x" },
                        "expectedOutputs" to listOf<String>(),
                    ),
                )
            repeat(50) { insertTestCase(id) }

            val oversized = api.createTestCase(id.toString(), tooMany, listOf(owner))
            val overLimit = api.createTestCase(id.toString(), "{\"inputs\":[],\"expectedOutputs\":[]}", listOf(owner))

            assertThat(oversized.status).isEqualTo(413)
            assertThat(oversized.body.get("limit").asString()).isEqualTo("definition")
            assertThat(overLimit.status).isEqualTo(413)
            assertThat(overLimit.body.get("limit").asString()).isEqualTo("testsPerSnippet")
            assertThat(
                api
                    .testCases(id.toString(), listOf(owner))
                    .body
                    .get("items")
                    .size(),
            ).isEqualTo(50)
        }

        @Test
        fun `failures are errors and never a creation or an empty list`() {
            val (owner) = actors()
            val id = ownedSnippet(owner)
            permissions.answerWith { StubResponse(500, "{}") }

            val creation = api.createTestCase(id.toString(), TRICKY_BODY, listOf(owner))
            val listing = api.testCases(id.toString(), listOf(owner))

            assertThat(creation.status).isEqualTo(502)
            assertThat(listing.status).isEqualTo(502)
            assertThat(listing.body.has("items")).isFalse()
            assertThat(api.testCases(id.toString(), emptyList()).status).isEqualTo(401)
            assertThat(
                api
                    .testCases("not-a-uuid")
                    .body
                    .get("field")
                    .asString(),
            ).isEqualTo("snippetId")
        }

        private fun assertDocumented(title: String) {
            val example = testsContract.example(title)
            val answer = sendDocumented(example)

            assertThat(answer.status).describedAs(title).isEqualTo(example.status)
            assertThat(answer.body).describedAs(title).isEqualTo(json.readTree(example.response))
        }

        private fun assertDocumentedCreation() {
            val example = testsContract.example("Crear un test")
            val answer = sendDocumented(example)
            val documented = json.readTree(example.response)

            assertThat(answer.status).isEqualTo(example.status)
            assertThat(withoutIds(answer)).isEqualTo(json.readTree(example.request.substringAfter("\n\n")))
            assertThat(answer.body.get("snippetId")).isEqualTo(documented.get("snippetId"))
            assertThat(answer.body.propertyNames().toList()).isEqualTo(documented.propertyNames().toList())
        }

        private fun sendDocumented(example: ContractExample): HttpAnswer {
            val lines = example.request.lines()
            val (method, path) = lines.first().split(" ")
            val snippetId = path.removePrefix("/snippets/").removeSuffix("/tests")
            val actor = lines[1].removePrefix("X-Dev-Actor-Id: ")

            return if (method == "POST") {
                api.createTestCase(snippetId, example.request.substringAfter("\n\n"), listOf(actor))
            } else {
                api.testCases(snippetId, listOf(actor))
            }
        }

        private fun withoutIds(answer: HttpAnswer) =
            json.createObjectNode().apply {
                set("inputs", answer.body.get("inputs"))
                set("expectedOutputs", answer.body.get("expectedOutputs"))
            }

        private fun actors(): List<String> = List(2) { "actor-${UUID.randomUUID()}" }

        private fun ownedSnippet(owner: String): UUID {
            val id = UUID.randomUUID()
            insertSnippet(id)
            ledger.own(owner, id)
            return id
        }

        private fun insertSnippet(
            id: UUID,
            state: String = "CONFIRMED",
        ) {
            jdbc.update(
                "INSERT INTO snippets (id, name, description, language, version, state, creation_key, " +
                    "creation_actor_id, creation_fingerprint) VALUES (?, 'Tested', NULL, 'printscript', '1.0', ?, ?, " +
                    "'someone-else', 'f')",
                id,
                state,
                UUID.randomUUID(),
            )
        }

        private fun insertTestCase(
            snippetId: UUID,
            id: UUID = UUID.randomUUID(),
        ) {
            jdbc.update(
                "INSERT INTO snippet_test_cases (id, snippet_id, inputs, expected_outputs) " +
                    "VALUES (?, ?, '[\"Juan\"]', '[\"Hola, Juan\"]')",
                id,
                snippetId,
            )
        }

        companion object {
            private val ledger = AccessLedger()
            private val permissions = ScriptedHttpService { request -> ledger.answer(request) }

            @JvmStatic
            @DynamicPropertySource
            fun dependencies(registry: DynamicPropertyRegistry) {
                registry.add("permissions.base-url") { permissions.baseUrl }
                registry.add("storage.local.directory") { Files.createTempDirectory("tests-http").toString() }
            }

            @JvmStatic
            @AfterAll
            fun stopDependencies() {
                permissions.close()
            }
        }
    }
