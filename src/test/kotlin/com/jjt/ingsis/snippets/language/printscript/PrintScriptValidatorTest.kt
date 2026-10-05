package com.jjt.ingsis.snippets.language.printscript

import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.language.ValidationResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class PrintScriptValidatorTest {
    private val validBody = """{"valid": true, "diagnostics": []}"""

    private val invalidBody =
        """
        {
          "valid": false,
          "diagnostics": [
            {
              "rule": "UNEXPECTED_TOKEN",
              "message": "se esperaba ';' pero se encontró 'println'",
              "line": 2,
              "column": 1
            }
          ]
        }
        """.trimIndent()

    private val unsupportedVersionBody =
        """
        {
          "title": "Unsupported version",
          "status": 422,
          "detail": "PrintScript version '2.0' is not supported.",
          "instance": "/validate",
          "supportedVersions": ["1.0", "1.1"]
        }
        """.trimIndent()

    @Test
    fun `sends the code and the version as the contract of the PrintScript service asks`() {
        StubLanguageService(status = 200, body = validBody).use { service ->
            validatorFor(service.baseUrl).validate("1.1", "println(1);")

            val request = service.received.single()

            assertThat(request.method).isEqualTo("POST")
            assertThat(request.path).isEqualTo("/validate")
            assertThat(request.contentType).startsWith("application/json")
            assertThat(request.body).isEqualTo("""{"code":"println(1);","version":"1.1"}""")
        }
    }

    @Test
    fun `accepts code that the service finds valid`() {
        StubLanguageService(status = 200, body = validBody).use { service ->
            assertThat(validatorFor(service.baseUrl).validate("1.0", "println(1);")).isEqualTo(ValidationResult.Valid)
        }
    }

    @Test
    fun `keeps the rule, the message, the line and the column of each diagnostic`() {
        StubLanguageService(status = 200, body = invalidBody).use { service ->
            val result = validatorFor(service.baseUrl).validate("1.0", "let total: number = 5\nprintln(total);")

            assertThat(result).isEqualTo(
                ValidationResult.Invalid(
                    listOf(
                        Diagnostic(
                            rule = "UNEXPECTED_TOKEN",
                            message = "se esperaba ';' pero se encontró 'println'",
                            line = 2,
                            column = 1,
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `ignores fields that a newer version of the service may add`() {
        val body = """{"valid": true, "diagnostics": [], "processorVersion": "1.1.0"}"""

        StubLanguageService(status = 200, body = body).use { service ->
            assertThat(validatorFor(service.baseUrl).validate("1.0", "println(1);")).isEqualTo(ValidationResult.Valid)
        }
    }

    @Test
    fun `reports a version that the service does not support`() {
        StubLanguageService(status = 422, body = unsupportedVersionBody).use { service ->
            assertThat(validatorFor(service.baseUrl).validate("2.0", "println(1);"))
                .isEqualTo(ValidationResult.UnsupportedVersion(language = "printscript", version = "2.0"))
        }
    }

    @Test
    fun `a server error is a failure and not invalid code`() {
        val body = """{"title": "Internal Server Error", "status": 500}"""

        StubLanguageService(status = 500, body = body).use { service ->
            assertThat(validatorFor(service.baseUrl).validate("1.0", "println(1);"))
                .isInstanceOf(ValidationResult.Failed::class.java)
        }
    }

    @Test
    fun `a request that the service cannot read is a failure and not invalid code`() {
        val body = """{"title": "Bad Request", "status": 400}"""

        StubLanguageService(status = 400, body = body).use { service ->
            assertThat(validatorFor(service.baseUrl).validate("1.0", "println(1);"))
                .isInstanceOf(ValidationResult.Failed::class.java)
        }
    }

    @Test
    fun `an answer that does not follow the contract is a failure`() {
        StubLanguageService(status = 200, body = "this is not JSON").use { service ->
            assertThat(validatorFor(service.baseUrl).validate("1.0", "println(1);"))
                .isInstanceOf(ValidationResult.Failed::class.java)
        }
    }

    @Test
    fun `a service that takes longer than the read timeout is a failure`() {
        StubLanguageService(status = 200, body = validBody, delay = Duration.ofSeconds(2)).use { service ->
            val validator =
                PrintScriptValidator(
                    PrintScriptProperties(baseUrl = service.baseUrl, readTimeout = Duration.ofMillis(200)),
                )

            assertThat(validator.validate("1.0", "println(1);")).isInstanceOf(ValidationResult.Failed::class.java)
        }
    }

    @Test
    fun `a service that is down is a failure`() {
        val service = StubLanguageService(status = 200, body = validBody)
        service.close()

        assertThat(validatorFor(service.baseUrl).validate("1.0", "println(1);"))
            .isInstanceOf(ValidationResult.Failed::class.java)
    }

    private fun validatorFor(baseUrl: String): PrintScriptValidator =
        PrintScriptValidator(PrintScriptProperties(baseUrl = baseUrl))
}
