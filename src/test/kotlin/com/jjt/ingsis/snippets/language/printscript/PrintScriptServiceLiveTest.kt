package com.jjt.ingsis.snippets.language.printscript

import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.language.ValidationResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@EnabledIfEnvironmentVariable(named = "PRINTSCRIPT_SERVICE_URL", matches = ".+")
class PrintScriptServiceLiveTest {
    private val validator =
        PrintScriptValidator(PrintScriptProperties(baseUrl = System.getenv("PRINTSCRIPT_SERVICE_URL")))

    private val codeOnlyValidIn11 = "const ready: boolean = true;\nif (ready) {\n  println(\"listo\");\n}"

    @Test
    fun `the real service accepts valid code of both versions`() {
        assertThat(validator.validate("1.0", "let total: number = 2 + 3;\nprintln(total);"))
            .isEqualTo(ValidationResult.Valid)
        assertThat(validator.validate("1.1", codeOnlyValidIn11)).isEqualTo(ValidationResult.Valid)
    }

    @Test
    fun `the real service rejects in 1_0 what only 1_1 allows, with rule, line and column`() {
        assertThat(validator.validate("1.0", codeOnlyValidIn11)).isEqualTo(
            ValidationResult.Invalid(
                listOf(
                    Diagnostic(
                        rule = "UNEXPECTED_TOKEN",
                        message = "se esperaba '=' pero se encontró 'ready'",
                        line = 1,
                        column = 7,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `the real service rejects a version it does not support`() {
        assertThat(validator.validate("2.0", "println(1);"))
            .isEqualTo(ValidationResult.UnsupportedVersion(language = "printscript", version = "2.0"))
    }
}
