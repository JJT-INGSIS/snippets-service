package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
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
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private const val RECIPIENT = "dev-thiago"
private const val STRANGER = "dev-ana"
private const val CODE = "let saludo: string = \"¡Hola! 🚀\";\nprintln(saludo);\r\n"

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["permissions.read-timeout=250ms"],
)
@ActiveProfiles("dev")
@Import(MetadataTestConfiguration::class)
class SharingHttpTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") port: Int,
        private val jdbc: JdbcTemplate,
        private val storage: SnippetContentStorage,
    ) {
        private val api = SnippetsApi(port)

        @BeforeEach
        fun resetDependencies() {
            permissions.reset()
        }

        @Test
        fun `documented shares and denials are the real HTTP responses`() {
            val id = UUID.fromString("0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40")
            stored(id)
            listOf(
                "Compartir por primera vez",
                "Repetir el mismo share",
                "Un usuario compartido intenta compartir",
                "Sin acceso",
                "Compartirse a uno mismo",
            ).forEach { title ->
                val example = sharingContract.example(title)
                val lines = example.request.lines()
                val recipient = lines.first().substringAfter("/shares/")
                val actor = lines[1].substringAfter("X-Dev-Actor-Id: ")
                val response = api.share(id.toString(), recipient, listOf(actor))
                assertThat(response.status).describedAs(title).isEqualTo(example.status)
                assertThat(response.body).describedAs(title).isEqualTo(json.readTree(example.response))
            }
        }

        @Test
        fun `sharing grants exact read access without allowing modification or resharing`() {
            val id = stored()
            assertThat(api.share(id.toString(), RECIPIENT).status).isEqualTo(201)
            assertThat(api.share(id.toString(), RECIPIENT).status).isEqualTo(200)
            val detail = api.detail(id.toString(), listOf(RECIPIENT))
            assertThat(detail.status).isEqualTo(200)
            assertThat(detail.body.get("code").asString()).isEqualTo(CODE)
            assertThat(
                detail.body
                    .get("capabilities")
                    .get("modify")
                    .asBoolean(),
            ).isFalse()
            assertThat(
                detail.body
                    .get("capabilities")
                    .get("share")
                    .asBoolean(),
            ).isFalse()
            val listed = api.list("?relation=shared", listOf(RECIPIENT))
            assertThat(listed.status).isEqualTo(200)
            assertThat(
                listed.body
                    .get("snippets")
                    .values()
                    .count { it.get("id").asString() == id.toString() },
            ).isEqualTo(1)
            assertThat(api.detail(id.toString(), listOf(STRANGER)).status).isEqualTo(404)
            assertThat(api.share(id.toString(), STRANGER, listOf(RECIPIENT)).status).isEqualTo(403)
            assertThat(api.share(id.toString(), RECIPIENT, listOf(STRANGER)).status).isEqualTo(404)
            val denied = api.update(id.toString(), requestBody(), listOf(RECIPIENT))
            assertThat(denied.status).isEqualTo(403)
            assertThat(denied.type).isEqualTo("urn:snippets:problem:modification-denied")
            assertThat(
                api
                    .detail(id.toString(), listOf(ACTOR))
                    .body
                    .get("code")
                    .asString(),
            ).isEqualTo(CODE)
        }

        @Test
        fun `invalid requests recipients pending and missing snippets cannot grant access`() {
            val pending = stored(state = "PENDING")
            assertThat(api.share(pending.toString(), RECIPIENT).status).isEqualTo(404)
            assertThat(api.share(UUID.randomUUID().toString(), RECIPIENT).status).isEqualTo(404)
            assertThat(api.share("bad-id", RECIPIENT).status).isEqualTo(400)
            assertThat(api.share(pending.toString(), RECIPIENT, emptyList()).status).isEqualTo(401)
            assertThat(api.share(pending.toString(), RECIPIENT, listOf(ACTOR, ACTOR)).status).isEqualTo(401)
            assertThat(api.share(pending.toString(), "unknown-user").status).isEqualTo(422)
            assertThat(api.share(pending.toString(), ACTOR).status).isEqualTo(422)
            assertThat(permissions.received).isEmpty()
        }

        @Test
        fun `broken dependency responses never report a confirmed share`() {
            val id = stored()
            listOf(StubResponse(500, "{}"), StubResponse(201, "{}"), StubResponse(403, "{}")).forEach { response ->
                permissions.answerWith { response }
                val answer = api.share(id.toString(), RECIPIENT)
                assertThat(answer.status).isEqualTo(502)
                assertThat(answer.type).isEqualTo("urn:snippets:problem:dependency-failure")
                assertThat(answer.body.has("userId")).isFalse()
            }
        }

        @Test
        fun `retry resolves a share committed before its acknowledgment timed out`() {
            val id = stored()
            val attempts = AtomicInteger()
            permissions.answerWith { request ->
                val response = ledger.answer(request)
                if (attempts.incrementAndGet() == 1) response.copy(delay = Duration.ofSeconds(1)) else response
            }
            val uncertain = api.share(id.toString(), RECIPIENT)
            assertThat(uncertain.status).isEqualTo(502)
            assertThat(attempts.get()).isEqualTo(1)
            val retried = api.share(id.toString(), RECIPIENT)
            assertThat(retried.status).isEqualTo(200)
            assertThat(retried.body.get("userId").asString()).isEqualTo(RECIPIENT)
            assertThat(attempts.get()).isEqualTo(2)
        }

        private fun stored(
            id: UUID = UUID.randomUUID(),
            state: String = "CONFIRMED",
        ): UUID {
            val reference = (storage.store(CODE) as StoreResult.Stored).reference
            jdbc.update(
                "INSERT INTO snippets (id, name, description, language, version, state, creation_key, " +
                    "creation_actor_id, creation_fingerprint, content_reference) VALUES (?, 'Shared example', null, " +
                    "'printscript', '1.1', ?, ?, ?, 'f', ?)",
                id,
                state,
                UUID.randomUUID(),
                ACTOR,
                reference.value,
            )
            ledger.own(ACTOR, id)
            return id
        }

        companion object {
            private val ledger = AccessLedger()
            private val permissions = ScriptedHttpService { ledger.answer(it) }

            @JvmStatic @DynamicPropertySource
            fun dependencies(registry: DynamicPropertyRegistry) {
                registry.add("permissions.base-url") { permissions.baseUrl }
                registry.add("storage.local.directory") { Files.createTempDirectory("sharing-http").toString() }
            }

            @JvmStatic @AfterAll
            fun stopDependencies() {
                permissions.close()
            }
        }
    }
