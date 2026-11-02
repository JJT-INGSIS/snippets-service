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

private const val INDENT_TWO = """{"rules":{"indent-inside-if":2}}"""

private val stamped = mapOf("Processor-Revision" to "sha256-v1:" + "e".repeat(64))

private val catalog =
    """
    {"version":"1.1","rules":[
      {"id":"enforce-spacing-around-equals","type":"boolean","default":false,"minimum":null,"maximum":null,
       "conflictsWith":["enforce-no-spacing-around-equals"],"description":"Un espacio alrededor de cada =."},
      {"id":"indent-inside-if","type":"integer","default":null,"minimum":0,"maximum":10,
       "conflictsWith":[],"description":"Espacios de sangría dentro de un if."}]}
    """.trimIndent()

private fun printScriptAnswer(request: StubRequest): StubResponse =
    when {
        request.path == "/formatting/rules" -> {
            StubResponse(200, catalog, headers = stamped)
        }

        request.path == "/formatting/rules/check" && request.body.contains(":11") -> {
            val outOfRange = """{"valid":false,"violations":[{"rule":"indent-inside-if","reason":"out-of-range"}]}"""
            StubResponse(200, outOfRange, headers = stamped)
        }

        request.path == "/formatting/rules/check" -> {
            StubResponse(200, """{"valid":true,"violations":[]}""", headers = stamped)
        }

        else -> {
            StubResponse(404, "{}")
        }
    }

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(MetadataTestConfiguration::class)
class FormattingRulesHttpTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") port: Int,
        private val jdbc: JdbcTemplate,
    ) {
        private val api = SnippetsApi(port)

        @BeforeEach
        fun restorePrintScript() {
            printScript.reset()
        }

        @Test
        fun `the owner reads the catalog defaults then saves real rules and gets the job to follow`() {
            val owner = actor()

            val initial = rulesOf(owner)
            assertThat(initial.status).isEqualTo(200)
            assertThat(initial.body.get("changed").asBoolean()).isFalse()
            assertThat(initial.body.get("jobId").isNull).isTrue()
            assertThat(values(initial)).containsExactly("false", "null")

            val saved = put(INDENT_TWO, owner)
            assertThat(saved.status).isEqualTo(200)
            assertThat(saved.body.get("changed").asBoolean()).isTrue()
            assertThat(values(saved)).containsExactly("false", "2")
            assertThat(ownerOfJob(saved.body.get("jobId").asString())).isEqualTo(owner)

            val repeated = put(INDENT_TWO, owner)
            assertThat(repeated.body.get("changed").asBoolean()).isFalse()
            assertThat(repeated.body.get("jobId").isNull).isTrue()
            assertThat(repeated.body.get("revision")).isEqualTo(saved.body.get("revision"))
            assertThat(values(rulesOf(owner))).containsExactly("false", "2")
            assertThat(jobsOf(owner)).isEqualTo(1)
        }

        @Test
        fun `invalid rules answer 422 with every violation and keep the previous rules`() {
            val owner = actor()
            put(INDENT_TWO, owner)

            val rejected = put("""{"rules":{"indent-inside-if":11}}""", owner)

            assertThat(rejected.status).isEqualTo(422)
            assertThat(rejected.type).isEqualTo("urn:snippets:problem:invalid-formatting-rules")
            assertThat(rejected.body.get("violations"))
                .isEqualTo(json.readTree("""[{"rule":"indent-inside-if","reason":"out-of-range"}]"""))
            assertThat(values(rulesOf(owner))).containsExactly("false", "2")
            assertThat(jobsOf(owner)).isEqualTo(1)
        }

        @Test
        fun `a body that is not a rules object is rejected before reaching PrintScript`() {
            val bodies =
                listOf(
                    """{"indent-inside-if":2}""",
                    """{"rules":[]}""",
                    """{"rules":{},"extra":1}""",
                    """{"rules":{"indent-inside-if":2,"indent-inside-if":4}}""",
                    "not json",
                )

            bodies.forEach { body ->
                assertThat(put(body, actor()).status).isEqualTo(400)
            }
            assertThat(printScript.received).isEmpty()
        }

        @Test
        fun `PrintScript down or without a verifiable revision answers 502 and keeps the rules`() {
            val owner = actor()
            val indentFour = """{"rules":{"indent-inside-if":4}}"""
            put(INDENT_TWO, owner)

            printScript.answerWith { StubResponse(500, """{"status":500}""") }
            assertThat(put(indentFour, owner).status).isEqualTo(502)

            printScript.answerWith { request -> printScriptAnswer(request).copy(headers = emptyMap()) }
            assertThat(put(indentFour, owner).status).isEqualTo(502)

            printScript.reset()
            assertThat(values(rulesOf(owner))).containsExactly("false", "2")
            assertThat(jobsOf(owner)).isEqualTo(1)
        }

        @Test
        fun `nobody sees or changes the rules of another identity`() {
            val juan = actor()
            val thiago = actor()
            put(INDENT_TWO, juan)

            assertThat(values(rulesOf(thiago))).containsExactly("false", "null")

            val spacing = """{"rules":{"enforce-spacing-around-equals":true}}"""
            put(spacing, thiago)

            assertThat(values(rulesOf(juan))).containsExactly("false", "2")
            assertThat(values(rulesOf(thiago))).containsExactly("true", "null")
        }

        @Test
        fun `without identity or with an unsupported version the request is rejected`() {
            assertThat(rulesOf(null).status).isEqualTo(401)
            assertThat(put(INDENT_TWO, null).status).isEqualTo(401)

            val unsupported = api.formattingRules("printscript", "9.9", listOf(actor()))

            assertThat(unsupported.status).isEqualTo(422)
            assertThat(unsupported.type).isEqualTo("urn:snippets:problem:unsupported-version")
            assertThat(printScript.received).isEmpty()
        }

        private fun put(
            body: String,
            owner: String?,
        ): HttpAnswer =
            api.saveFormattingRules(
                language = "printscript",
                version = "1.1",
                body = body,
                actors = listOfNotNull(owner),
            )

        private fun rulesOf(owner: String?): HttpAnswer =
            api.formattingRules("printscript", "1.1", listOfNotNull(owner))

        private fun actor(): String = "dev-" + UUID.randomUUID()

        private fun values(answer: HttpAnswer): List<String> =
            answer.body
                .get("rules")
                .toList()
                .map { it.get("value").toString() }

        private fun ownerOfJob(jobId: String): String? =
            jdbc.queryForObject(
                "SELECT scope ->> 'ownerId' FROM background_jobs WHERE id = ?",
                String::class.java,
                UUID.fromString(jobId),
            )

        private fun jobsOf(owner: String): Int =
            jdbc.queryForObject(
                "SELECT count(*) FROM background_jobs WHERE scope ->> 'ownerId' = ?",
                Int::class.java,
                owner,
            ) ?: 0

        companion object {
            private fun temporaryStorage(): String = Files.createTempDirectory("formatting-rules-http").toString()

            private val printScript = ScriptedHttpService(::printScriptAnswer)

            @JvmStatic
            @DynamicPropertySource
            fun dependencies(registry: DynamicPropertyRegistry) {
                registry.add("language.printscript.base-url") { printScript.baseUrl }
                registry.add("storage.local.directory") { temporaryStorage() }
            }

            @JvmStatic
            @AfterAll
            fun stopPrintScript() {
                printScript.close()
            }
        }
    }
