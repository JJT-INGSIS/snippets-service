package com.jjt.ingsis.snippets.language

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LanguageTest {
    private val diagnostics = listOf(Diagnostic(rule = "ANY_RULE", message = "any message", line = 3, column = 7))

    private val language = Language(listOf(FixedValidator("printscript", ValidationResult.Invalid(diagnostics))))

    @Test
    fun `asks the validator of the requested language and returns its answer untouched`() {
        assertThat(language.validate("printscript", "1.0", "println(1);"))
            .isEqualTo(ValidationResult.Invalid(diagnostics))
    }

    @Test
    fun `rejects a language that has no validator`() {
        assertThat(language.validate("python", "3.12", "print(1)"))
            .isEqualTo(ValidationResult.UnsupportedLanguage("python"))
    }

    private class FixedValidator(
        override val language: String,
        private val result: ValidationResult,
    ) : LanguageValidator {
        override fun validate(
            version: String,
            code: String,
        ): ValidationResult = result
    }
}
