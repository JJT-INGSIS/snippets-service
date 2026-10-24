package com.jjt.ingsis.snippets.language

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
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

    @Test
    fun `knows the file extension of each supported language`() {
        assertThat(language.extensionOf("printscript")).isEqualTo("txt")
        assertThat(language.extensionOf("python")).isNull()
    }

    @Test
    fun `rejects an unadvertised version before delegating to an unavailable validator`() {
        val unavailable = Language(listOf(FixedValidator("printscript", ValidationResult.Failed("offline"))))

        assertThat(unavailable.validate("printscript", "2.0", "println(1);"))
            .isEqualTo(ValidationResult.UnsupportedVersion("printscript", "2.0"))
        assertThat(unavailable.validate("printscript", "1.1", "println(1);"))
            .isEqualTo(ValidationResult.Failed("offline"))
    }

    @Test
    fun `catalog is deterministic and uses the same capabilities as validation and extensions`() {
        val registry =
            Language(
                listOf(
                    FixedValidator("zeta", ValidationResult.Valid, supportedVersions = setOf("2.0", "1.0")),
                    FixedValidator("alpha", ValidationResult.Valid),
                ),
            )

        assertThat(registry.supportedLanguages()).containsExactly(
            SupportedLanguage("alpha", "txt", listOf("1.0", "1.1")),
            SupportedLanguage("zeta", "txt", listOf("1.0", "2.0")),
        )
        registry.supportedLanguages().forEach { entry ->
            assertThat(registry.extensionOf(entry.language)).isEqualTo(entry.extension)
            entry.versions.forEach { version ->
                assertThat(registry.validate(entry.language, version, "code")).isEqualTo(ValidationResult.Valid)
            }
        }
    }

    @Test
    fun `rejects ambiguous validators instead of merging their versions`() {
        assertThatIllegalArgumentException().isThrownBy {
            Language(
                listOf(FixedValidator("same", ValidationResult.Valid), FixedValidator("same", ValidationResult.Valid)),
            )
        }
    }

    @Test
    fun `rejects invalid declared capabilities during configuration`() {
        val invalid =
            listOf(
                FixedValidator(" ", ValidationResult.Valid),
                FixedValidator(" padded", ValidationResult.Valid),
                FixedValidator("valid", ValidationResult.Valid, extension = ".prs"),
                FixedValidator("valid", ValidationResult.Valid, supportedVersions = emptySet()),
                FixedValidator("valid", ValidationResult.Valid, supportedVersions = setOf(" ")),
                FixedValidator("valid", ValidationResult.Valid, supportedVersions = setOf("1.0 ")),
            )

        invalid.forEach { validator ->
            assertThatIllegalArgumentException().isThrownBy { Language(listOf(validator)) }
        }
    }

    private class FixedValidator(
        override val language: String,
        private val result: ValidationResult,
        override val extension: String = "txt",
        override val supportedVersions: Set<String> = setOf("1.0", "1.1"),
    ) : LanguageValidator {
        override fun validate(
            version: String,
            code: String,
        ): ValidationResult = result
    }
}
