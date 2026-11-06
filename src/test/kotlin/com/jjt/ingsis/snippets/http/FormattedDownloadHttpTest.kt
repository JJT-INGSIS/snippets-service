package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.formattingrules.FormattingSelection
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

private const val UNICODE_SOURCE = "let saludo:string=\"¡Hola, señor! 🚀\";\nprintln(saludo);"
private const val UNICODE_FORMATTED = "let saludo: string = \"¡Hola, señor! 🚀\";\nprintln(saludo);\n"
private const val STORED_VARIANT = "let saludo: string = \"stored\";\n"

private val formatRevision = "sha256-v1:" + "e".repeat(64)

private val olderFormatRevision = "sha256-v1:" + "d".repeat(64)

private val revisionHeader = mapOf("Processor-Revision" to formatRevision)

private val indentCatalog =
    """
    {"version":"1.1","rules":[
      {"id":"indent-inside-if","type":"integer","default":null,"minimum":0,"maximum":10,
       "conflictsWith":[],"description":"Espacios de sangría dentro de un if."}]}
    """.trimIndent()

private fun formattingAnswer(request: StubRequest): StubResponse =
    when {
        request.path == "/linting/rules" -> {
            StubResponse(200, """{"version":"1.1","rules":[],"defaults":{}}""", headers = revisionHeader)
        }

        request.path == "/formatting/rules" -> {
            StubResponse(200, indentCatalog, headers = revisionHeader)
        }

        request.path == "/processor" -> {
            StubResponse(200, """{"capabilities":{"format":"$formatRevision"}}""")
        }

        request.path == "/formatting/rules/check" -> {
            StubResponse(200, """{"valid":true,"violations":[]}""", headers = revisionHeader)
        }

        request.path == "/formatting" && request.body.contains("indent-inside-if") -> {
            val body = """{"formatted":true,"code":"with-owner-rules","diagnostics":[]}"""
            StubResponse(200, body, headers = revisionHeader)
        }

        request.path == "/formatting" -> {
            val answer = mapOf("formatted" to true, "code" to UNICODE_FORMATTED, "diagnostics" to emptyList<Any>())
            val body = json.writeValueAsString(answer)
            StubResponse(200, body, headers = revisionHeader)
        }

        else -> {
            StubResponse(404, "{}")
        }
    }

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["permissions.read-timeout=500ms"],
)
@ActiveProfiles("dev")
@Import(MetadataTestConfiguration::class)
class FormattedDownloadHttpTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") port: Int,
        private val jdbc: JdbcTemplate,
        private val storage: SnippetContentStorage,
    ) {
        private val api = SnippetsApi(port)

        @BeforeEach
        fun restoreDependencies() {
            printScript.reset()
            permissions.reset()
        }

        @Test
        fun `the owner downloads the saved code formatted now with its real name extension and unicode`() {
            val owner = actor()
            val id = snippet(owner, "Saludo", UNICODE_SOURCE)

            val download = api.formatted(id.toString(), listOf(owner))

            assertThat(download.status).isEqualTo(200)
            assertThat(download.body.get("snippetId").asString()).isEqualTo(id.toString())
            assertThat(download.body.get("name").asString()).isEqualTo("Saludo")
            assertThat(download.body.get("extension").asString()).isEqualTo("prs")
            assertThat(download.body.get("version").asString()).isEqualTo("1.1")
            assertThat(download.body.get("code").asString()).isEqualTo(UNICODE_FORMATTED)
            assertThat(download.body.get("source").asString()).isEqualTo("on-demand")
            assertThat(download.body.get("rulesRevision").asString()).isEqualTo(FormattingSelection.EMPTY.revision)
            assertThat(download.body.get("processorRevision").asString()).isEqualTo(formatRevision)
            assertThat(
                api
                    .detail(id.toString(), listOf(owner))
                    .body
                    .get("code")
                    .asString(),
            ).isEqualTo(UNICODE_SOURCE)
            assertThat(variantsOf(id)).isZero()
        }

        @Test
        fun `a reader gets the variant of the owner rules and a stranger gets nothing`() {
            val (owner, reader, stranger) = List(3) { actor() }
            val id = snippet(owner, "Compartido", "println(1);")
            ledger.share(owner, id, reader)
            api.saveFormattingRules(
                language = "printscript",
                version = "1.1",
                body = """{"rules":{"indent-inside-if":2}}""",
                actors = listOf(owner),
            )
            printScript.received.clear()

            val forReader = api.formatted(id.toString(), listOf(reader))
            val forStranger = api.formatted(id.toString(), listOf(stranger))

            assertThat(forReader.status).isEqualTo(200)
            assertThat(forReader.body.get("code").asString()).isEqualTo("with-owner-rules")
            assertThat(forStranger.status).isEqualTo(404)
            assertThat(forStranger.type).isEqualTo("urn:snippets:problem:snippet-not-found")
            assertThat(forStranger.body.has("code")).isFalse()
            assertThat(printScript.received.filter { it.path == "/formatting" }).hasSize(1)
        }

        @Test
        fun `a current stored variant is downloaded without formatting and an older one is not`() {
            val owner = actor()
            val id = snippet(owner, "Guardado", UNICODE_SOURCE)
            publishVariant(id, formatRevision)

            val current = api.formatted(id.toString(), listOf(owner))

            assertThat(current.body.get("source").asString()).isEqualTo("stored")
            assertThat(current.body.get("code").asString()).isEqualTo(STORED_VARIANT)
            assertThat(printScript.received.map { it.path }).doesNotContain("/formatting")

            publishVariant(id, olderFormatRevision)
            val older = api.formatted(id.toString(), listOf(owner))

            assertThat(older.body.get("source").asString()).isEqualTo("on-demand")
            assertThat(older.body.get("code").asString()).isEqualTo(UNICODE_FORMATTED)
        }

        @Test
        fun `PrintScript down answers 502 without losing the original`() {
            val owner = actor()
            val id = snippet(owner, "Caido", UNICODE_SOURCE)
            publishVariant(id, formatRevision)
            printScript.answerWith { StubResponse(500, """{"status":500}""") }

            val download = api.formatted(id.toString(), listOf(owner))

            assertThat(download.status).isEqualTo(502)
            assertThat(download.type).isEqualTo("urn:snippets:problem:dependency-failure")
            assertThat(download.body.has("code")).isFalse()
            // Detail now verifies current lint provenance too; a provider outage cannot certify it.
            assertThat(api.detail(id.toString(), listOf(owner)).status).isEqualTo(502)
            printScript.reset()
            assertThat(
                api
                    .detail(id.toString(), listOf(owner))
                    .body
                    .get("code")
                    .asString(),
            ).isEqualTo(UNICODE_SOURCE)
        }

        @Test
        fun `code that cannot be formatted or is too large answers its own problem`() {
            val owner = actor()
            val id = snippet(owner, "Roto", "println(\"abc);")
            val lexicalError =
                """
                {"formatted":false,"code":null,"diagnostics":[
                  {"rule":"UNTERMINATED_STRING","message":"falta cerrar el texto","line":1,"column":9}]}
                """.trimIndent()

            printScript.answerWith { request ->
                if (request.path == "/formatting") StubResponse(200, lexicalError) else formattingAnswer(request)
            }
            val unformattable = api.formatted(id.toString(), listOf(owner))
            assertThat(unformattable.status).isEqualTo(422)
            assertThat(unformattable.type).isEqualTo("urn:snippets:problem:unformattable-code")
            assertThat(
                unformattable.body
                    .get("diagnostics")
                    .single()
                    .get("rule")
                    .asString(),
            ).isEqualTo("UNTERMINATED_STRING")

            printScript.answerWith { request ->
                if (request.path == "/formatting") {
                    StubResponse(413, """{"type":"urn:printscript:problem:request-too-large","status":413}""")
                } else {
                    formattingAnswer(request)
                }
            }
            val tooLarge = api.formatted(id.toString(), listOf(owner))
            assertThat(tooLarge.status).isEqualTo(422)
            assertThat(tooLarge.type).isEqualTo("urn:snippets:problem:formatting-limit-exceeded")
            assertThat(tooLarge.body.get("limit").asString()).isEqualTo("code")
        }

        @Test
        fun `invalid ids and missing identity are rejected before formatting`() {
            val invalid = api.formatted("not-a-uuid")
            assertThat(invalid.status).isEqualTo(400)
            assertThat(invalid.body.get("field").asString()).isEqualTo("snippetId")
            assertThat(api.formatted(UUID.randomUUID().toString(), actors = emptyList()).status).isEqualTo(401)
            assertThat(printScript.received).isEmpty()
        }

        private fun actor(): String = "dev-" + UUID.randomUUID()

        private fun snippet(
            owner: String,
            name: String,
            code: String,
        ): UUID {
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO snippets (id, name, description, language, version, state, creation_key, " +
                    "creation_actor_id, creation_fingerprint, content_reference) " +
                    "VALUES (?, ?, NULL, 'printscript', '1.1', 'CONFIRMED', ?, ?, 'f', ?)",
                id,
                name,
                UUID.randomUUID(),
                owner,
                stored(code).value,
            )
            ledger.own(owner, id)
            return id
        }

        private fun publishVariant(
            id: UUID,
            processorRevision: String,
        ) {
            val job = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO background_jobs (id, event_id, reaction, scope, revisions, next_attempt_at, " +
                    "created_at, updated_at) VALUES (?, ?, 'format-owned-snippets', '{}'::jsonb, '{}'::jsonb, " +
                    "now(), now(), now())",
                job,
                UUID.randomUUID(),
            )
            jdbc.update("DELETE FROM formatted_snippets WHERE snippet_id = ?", id)
            jdbc.update(
                "INSERT INTO formatted_snippets (snippet_id, source_reference, language, version, rules_revision, " +
                    "processor_revision, derived_reference, job_id, published_at) " +
                    "SELECT id, content_reference, language, version, ?, ?, ?, ?, now() FROM snippets WHERE id = ?",
                FormattingSelection.EMPTY.revision,
                processorRevision,
                stored(STORED_VARIANT).value,
                job,
                id,
            )
        }

        private fun stored(code: String): ContentReference = (storage.store(code) as StoreResult.Stored).reference

        private fun variantsOf(id: UUID): Int =
            jdbc.queryForObject("SELECT count(*) FROM formatted_snippets WHERE snippet_id = ?", Int::class.java, id)
                ?: 0

        companion object {
            private val ledger = AccessLedger()
            private val permissions = ScriptedHttpService { request -> ledger.answer(request) }
            private val printScript = ScriptedHttpService(::formattingAnswer)

            @JvmStatic
            @DynamicPropertySource
            fun dependencies(registry: DynamicPropertyRegistry) {
                registry.add("permissions.base-url") { permissions.baseUrl }
                registry.add("language.printscript.base-url") { printScript.baseUrl }
                registry.add("storage.local.directory") { Files.createTempDirectory("formatted-download").toString() }
            }

            @JvmStatic
            @AfterAll
            fun stopDependencies() {
                permissions.close()
                printScript.close()
            }
        }
    }
