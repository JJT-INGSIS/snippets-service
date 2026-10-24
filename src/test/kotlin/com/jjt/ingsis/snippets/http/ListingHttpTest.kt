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
import java.time.Duration
import java.util.UUID

private val hello = UUID.fromString("0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40")
private val sum = UUID.fromString("7d2e9a4f-1b3c-4e5d-8f6a-0b1c2d3e4f50")
private val welcome = UUID.fromString("9c1d2e3f-4a5b-4c6d-8e7f-a0b1c2d3e4f5")

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["permissions.read-timeout=500ms"],
)
@ActiveProfiles("dev")
@Import(MetadataTestConfiguration::class)
class ListingHttpTest
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
        fun `the documented listing examples are the real responses`() {
            documentedSnippets()
            val titles =
                listOf(
                    "Primera página, propios y compartidos",
                    "Solo compartidos, filtrados por nombre",
                    "Página posterior a la última",
                    "Tamaño de página fuera de rango",
                    "Filtro por lint",
                )

            titles.map(readingContract::example).forEach { example -> assertDocumented(example) }
            permissions.answerWith { StubResponse(500, "{}") }
            assertDocumented(readingContract.example("Permissions no disponible"))
        }

        @Test
        fun `each relation shows exactly the snippets the actor can read`() {
            val (actor, other) = actors()
            val own = List(2) { index -> stored("own-$index", owner = actor) }
            val shared = stored("shared", owner = other, recipients = listOf(actor))
            stored("foreign", owner = other)
            ledger.own(actor, row(name = "own-pending", language = "printscript", state = "PENDING", creator = actor))
            ledger.own(actor, UUID.randomUUID())

            assertThat(ids(actor, "all")).containsExactlyElementsOf(own + shared)
            assertThat(ids(actor, "owned")).containsExactlyElementsOf(own)
            assertThat(ids(actor, "shared")).containsExactly(shared)
            assertThat(
                api
                    .list("?relation=all", listOf(actor))
                    .body
                    .get("count")
                    .asLong(),
            ).isEqualTo(3)
        }

        @Test
        fun `owner and relation come from permissions and not from the creator`() {
            val (owner, recipient) = actors()
            val id = row(name = "borrowed", language = "printscript", state = "CONFIRMED", creator = recipient)
            ledger.own(owner, id)
            ledger.share(owner, id, recipient)

            val item =
                api
                    .list("", listOf(recipient))
                    .body
                    .get("snippets")
                    .single()

            assertThat(item.get("id").asString()).isEqualTo(id.toString())
            assertThat(item.get("ownerId").asString()).isEqualTo(owner)
            assertThat(item.get("relation").asString()).isEqualTo("shared")
            assertThat(item.get("extension").asString()).isEqualTo("prs")
            assertThat(item.get("lint").get("status").asString()).isEqualTo("not-implemented")
            assertThat(item.has("code")).isFalse()
        }

        @Test
        fun `filters order and pages work across actors without reading code`() {
            val (actor, other) = actors()
            val beta = stored("beta", owner = actor)
            val alpha = stored("Alpha", owner = other, recipients = listOf(actor))
            val gamma = stored("gamma", owner = actor, language = "python")
            stored("alphabet", owner = other)

            assertThat(ids(actor, "all&name=ALP")).containsExactly(alpha)
            assertThat(ids(actor, "all&language=python")).containsExactly(gamma)
            assertThat(ids(actor, "all&direction=desc")).containsExactly(gamma, beta, alpha)
            assertThat(ids(actor, "all&page=1&page_size=2")).containsExactly(gamma)
            val empty = api.list("?name=zzz", listOf(actor))
            assertThat(empty.status).isEqualTo(200)
            assertThat(empty.body.get("count").asLong()).isZero()
            assertThat(empty.body.get("snippets").isEmpty).isTrue()
        }

        @Test
        fun `permissions failures are failures and never an empty list`() {
            listOf(StubResponse(200, "{}"), StubResponse(503, "{}"), StubResponse(200, "{}", delay = slow))
                .forEach { failure ->
                    permissions.answerWith { failure }

                    val answer = api.list("")

                    assertThat(answer.status).isEqualTo(502)
                    assertThat(answer.type).isEqualTo("urn:snippets:problem:dependency-failure")
                    assertThat(answer.body.has("snippets")).isFalse()
                }
        }

        @Test
        fun `rejected requests never reach permissions`() {
            assertThat(api.list("", actors = emptyList()).status).isEqualTo(401)
            assertThat(api.list("", actors = listOf("a", "b")).status).isEqualTo(401)
            val repeated = api.list("?page=0&page=1")
            assertThat(repeated.status).isEqualTo(400)
            assertThat(repeated.body.get("parameter").asString()).isEqualTo("page")
            assertThat(permissions.received).isEmpty()
        }

        private fun assertDocumented(example: ContractExample) {
            val lines = example.request.lines()
            val path = lines.first().removePrefix("GET ")
            val actor = lines.drop(1).single().removePrefix("X-Dev-Actor-Id: ")
            val answer = api.list(path.removePrefix("/snippets"), listOf(actor))

            assertThat(answer.status).describedAs(example.title).isEqualTo(example.status)
            assertThat(answer.body).describedAs(example.title).isEqualTo(json.readTree(example.response))
        }

        private fun documentedSnippets() {
            insert(id = hello, name = "Hello", description = null, version = "1.0")
            insert(id = sum, name = "Sum", description = "Adds two numbers", version = "1.1")
            insert(id = welcome, name = "Welcome", description = null, version = "1.1")
            ledger.own(ACTOR, hello)
            ledger.own(ACTOR, welcome)
            ledger.share("dev-thiago", sum, ACTOR)
        }

        private fun ids(
            actor: String,
            relation: String,
        ): List<UUID> =
            api
                .list("?relation=$relation", listOf(actor))
                .body
                .get("snippets")
                .values()
                .map { UUID.fromString(it.get("id").asString()) }

        private fun actors(): List<String> = List(2) { "actor-${UUID.randomUUID()}" }

        private fun stored(
            name: String,
            owner: String,
            recipients: List<String> = emptyList(),
            language: String = "printscript",
        ): UUID {
            val id = row(name = name, language = language, state = "CONFIRMED", creator = owner)
            ledger.own(owner, id)
            recipients.forEach { recipient -> ledger.share(owner, id, recipient) }
            return id
        }

        private fun row(
            name: String,
            language: String,
            state: String,
            creator: String,
        ): UUID {
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO snippets (id, name, description, language, version, state, creation_key, " +
                    "creation_actor_id, creation_fingerprint) VALUES (?, ?, NULL, ?, '1.0', ?, ?, ?, 'f')",
                id,
                name,
                language,
                state,
                UUID.randomUUID(),
                creator,
            )
            return id
        }

        private fun insert(
            id: UUID,
            name: String,
            description: String?,
            version: String,
        ) {
            jdbc.update(
                "INSERT INTO snippets (id, name, description, language, version, state, creation_key, " +
                    "creation_actor_id, creation_fingerprint) VALUES (?, ?, ?, 'printscript', ?, 'CONFIRMED', ?, " +
                    "'someone-else', 'f') ON CONFLICT (id) DO NOTHING",
                id,
                name,
                description,
                version,
                UUID.randomUUID(),
            )
        }

        companion object {
            private val slow = Duration.ofSeconds(1)
            private val ledger = AccessLedger()
            private val permissions = ScriptedHttpService { request -> ledger.answer(request) }

            @JvmStatic
            @DynamicPropertySource
            fun dependencies(registry: DynamicPropertyRegistry) {
                registry.add("permissions.base-url") { permissions.baseUrl }
            }

            @JvmStatic
            @AfterAll
            fun stopDependencies() {
                permissions.close()
            }
        }
    }
