package com.jjt.ingsis.snippets.language.printscript

import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.language.PROCESSOR_REVISION_HEADER
import com.jjt.ingsis.snippets.language.ProcessorIdentity
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.language.formatting.FormattingCatalogResult
import com.jjt.ingsis.snippets.language.formatting.FormattingResult
import com.jjt.ingsis.snippets.language.formatting.FormattingRule
import com.jjt.ingsis.snippets.language.formatting.FormattingRuleViolation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PrintScriptFormatterTest {
    private val formattedBody = """{"formatted":true,"code":"let a = 1;","diagnostics":[]}"""

    private val unsupportedVersionBody =
        """{"status":422,"title":"Unsupported version","supportedVersions":["1.0","1.1"]}"""

    @Test
    fun `sends the code, the version and the rules as the PrintScript contract asks`() {
        val rules = mapOf("enforce-spacing-around-equals" to true, "indent-inside-if" to null)

        StubLanguageService(status = 200, body = formattedBody).use { service ->
            formatterFor(service.baseUrl).format("1.1", "let a=1;", rules)

            val request = service.received.single()
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.path).isEqualTo("/formatting")
            assertThat(request.contentType).startsWith("application/json")
            assertThat(request.body).isEqualTo(
                """{"code":"let a=1;","version":"1.1",""" +
                    """"rules":{"enforce-spacing-around-equals":true,"indent-inside-if":null}}""",
            )
        }
    }

    @Test
    fun `returns the formatted code with the revision that produced it`() {
        val revision = "sha256-v1:" + "b".repeat(64)
        val stamped = mapOf(PROCESSOR_REVISION_HEADER to revision)

        StubLanguageService(status = 200, body = formattedBody, headers = stamped).use { service ->
            val attributed = formatterFor(service.baseUrl).format("1.0", "let a=1;", emptyMap())

            assertThat(attributed.result).isEqualTo(FormattingResult.Formatted("let a = 1;"))
            assertThat(attributed.processor).isEqualTo(ProcessorIdentity.Verified(ProcessorRevision(revision)))
        }
    }

    @Test
    fun `code with a lexical error is unformattable with its diagnostic`() {
        val body =
            """
            {"formatted":false,"code":null,"diagnostics":[
              {"rule":"UNTERMINATED_STRING","message":"falta cerrar el texto","line":1,"column":9}]}
            """.trimIndent()

        StubLanguageService(status = 200, body = body).use { service ->
            assertThat(formatterFor(service.baseUrl).format("1.0", "println(\"abc);", emptyMap()).result).isEqualTo(
                FormattingResult.Unformattable(
                    listOf(
                        Diagnostic(
                            rule = "UNTERMINATED_STRING",
                            message = "falta cerrar el texto",
                            line = 1,
                            column = 9,
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `each typed rejection of the service keeps its meaning`() {
        val invalidRules =
            """
            {"type":"urn:printscript:problem:invalid-formatting-rules","status":422,
             "violations":[{"rule":"indent-inside-if","reason":"unavailable-in-version"}]}
            """.trimIndent()

        assertThat(formatWith(422, invalidRules)).isEqualTo(
            FormattingResult.InvalidRules(
                listOf(FormattingRuleViolation("indent-inside-if", "unavailable-in-version")),
            ),
        )
        assertThat(formatWith(422, """{"type":"urn:printscript:problem:formatted-output-too-large","status":422}"""))
            .isEqualTo(FormattingResult.OutputTooLarge)
        assertThat(formatWith(400, """{"type":"urn:printscript:problem:request-too-large","status":400}"""))
            .isEqualTo(FormattingResult.CodeTooLarge)
        assertThat(formatWith(422, unsupportedVersionBody))
            .isEqualTo(FormattingResult.UnsupportedVersion("printscript", "1.1"))
    }

    @Test
    fun `an unexpected answer is a failure and never invalid code`() {
        val unexpected =
            listOf(
                400 to """{"title":"Bad Request","status":400}""",
                422 to "not json",
                500 to """{"status":500}""",
                200 to "not json",
                200 to """{"formatted":true,"code":null,"diagnostics":[]}""",
            )

        unexpected.forEach { (status, body) ->
            assertThat(formatWith(status, body)).isInstanceOf(FormattingResult.Failed::class.java)
        }
    }

    @Test
    fun `a service that is down is a failure`() {
        val service = StubLanguageService(status = 200, body = "{}")
        service.close()

        assertThat(formatterFor(service.baseUrl).format("1.0", "1;", emptyMap()).result)
            .isInstanceOf(FormattingResult.Failed::class.java)
        assertThat(formatterFor(service.baseUrl).catalog("1.0").result)
            .isInstanceOf(FormattingCatalogResult.Failed::class.java)
    }

    @Test
    fun `reads the catalog of a version`() {
        val body =
            """
            {"version":"1.1","rules":[
              {"id":"indent-inside-if","type":"integer","default":null,"minimum":0,"maximum":10,
               "conflictsWith":[],"description":"Cantidad de espacios de sangría dentro de un if."}]}
            """.trimIndent()

        StubLanguageService(status = 200, body = body).use { service ->
            val attributed = formatterFor(service.baseUrl).catalog("1.1")

            assertThat(service.received.single().path).isEqualTo("/formatting/rules")
            assertThat(attributed.result).isEqualTo(
                FormattingCatalogResult.Catalog(
                    listOf(
                        FormattingRule(
                            id = "indent-inside-if",
                            type = "integer",
                            default = null,
                            minimum = 0,
                            maximum = 10,
                            conflictsWith = emptyList(),
                            description = "Cantidad de espacios de sangría dentro de un if.",
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `the catalog of an unsupported version is reported as such`() {
        StubLanguageService(status = 422, body = unsupportedVersionBody).use { service ->
            assertThat(formatterFor(service.baseUrl).catalog("9.9").result)
                .isEqualTo(FormattingCatalogResult.UnsupportedVersion("printscript", "9.9"))
        }
    }

    private fun formatWith(
        status: Int,
        body: String,
    ): FormattingResult =
        StubLanguageService(status = status, body = body).use { service ->
            formatterFor(service.baseUrl).format("1.1", "println(1);", emptyMap()).result
        }

    private fun formatterFor(baseUrl: String): PrintScriptFormatter =
        PrintScriptFormatter(PrintScriptProperties(baseUrl = baseUrl))
}
