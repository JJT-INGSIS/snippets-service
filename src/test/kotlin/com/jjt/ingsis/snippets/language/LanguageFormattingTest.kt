package com.jjt.ingsis.snippets.language

import com.jjt.ingsis.snippets.language.formatting.FormattingCatalogResult
import com.jjt.ingsis.snippets.language.formatting.FormattingCheckResult
import com.jjt.ingsis.snippets.language.formatting.FormattingResult
import com.jjt.ingsis.snippets.language.formatting.LanguageFormatter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class LanguageFormattingTest {
    private val revision = ProcessorIdentity.Verified(ProcessorRevision("sha256-v1:" + "c".repeat(64)))

    private val formatter = FixedFormatter("printscript", revision)

    private val language = Language(listOf(FixedValidator("printscript")), formatters = listOf(formatter))

    @Test
    fun `asks the formatter of the language and keeps its answer and revision`() {
        val rules = mapOf<String, Any?>("enforce-spacing-around-equals" to true)

        assertThat(language.format(language = "printscript", version = "1.1", code = "let a=1;", rules = rules))
            .isEqualTo(Attributed(FormattingResult.Formatted("let a=1;|1.1|$rules"), revision))
        assertThat(language.formattingCatalog("printscript", "1.0"))
            .isEqualTo(Attributed(FormattingCatalogResult.Catalog(emptyList()), revision))
    }

    @Test
    fun `checks rules with the formatter of the language and keeps its answer and revision`() {
        val rules = mapOf<String, Any?>("indent-inside-if" to 2)

        assertThat(language.checkFormattingRules("printscript", "1.1", rules))
            .isEqualTo(Attributed(FormattingCheckResult.Accepted(mapOf("checked" to "1.1|$rules")), revision))
    }

    @Test
    fun `a language without formatter is unsupported`() {
        val validatorOnly = Language(listOf(FixedValidator("printscript")))

        assertThat(
            validatorOnly.format(language = "printscript", version = "1.0", code = "1;", rules = emptyMap()).result,
        ).isEqualTo(FormattingResult.UnsupportedLanguage("printscript"))
        assertThat(language.formattingCatalog("python", "3.12").result)
            .isEqualTo(FormattingCatalogResult.UnsupportedLanguage("python"))
        assertThat(language.checkFormattingRules("python", "3.12", emptyMap()).result)
            .isEqualTo(FormattingCheckResult.UnsupportedLanguage("python"))
    }

    @Test
    fun `an unadvertised version is rejected without asking the formatter`() {
        assertThat(language.format(language = "printscript", version = "2.0", code = "1;", rules = emptyMap()))
            .isEqualTo(Attributed(FormattingResult.UnsupportedVersion("printscript", "2.0"), ProcessorIdentity.Missing))
        assertThat(language.formattingCatalog("printscript", "2.0").result)
            .isEqualTo(FormattingCatalogResult.UnsupportedVersion("printscript", "2.0"))
        assertThat(language.checkFormattingRules("printscript", "2.0", emptyMap()).result)
            .isEqualTo(FormattingCheckResult.UnsupportedVersion("printscript", "2.0"))
        assertThat(formatter.calls).isZero()
    }

    @Test
    fun `rejects two formatters for the same language`() {
        assertThatIllegalArgumentException().isThrownBy {
            Language(
                emptyList(),
                formatters = listOf(FixedFormatter("same", revision), FixedFormatter("same", revision)),
            )
        }
    }

    private class FixedValidator(
        override val language: String,
    ) : LanguageValidator {
        override val extension: String = "prs"

        override val supportedVersions: Set<String> = setOf("1.0", "1.1")

        override fun validate(
            version: String,
            code: String,
        ): ValidationResult = ValidationResult.Valid
    }

    private class FixedFormatter(
        override val language: String,
        private val processor: ProcessorIdentity,
    ) : LanguageFormatter {
        var calls = 0
            private set

        override fun catalog(version: String): Attributed<FormattingCatalogResult> {
            calls++
            return Attributed(FormattingCatalogResult.Catalog(emptyList()), processor)
        }

        override fun format(
            version: String,
            code: String,
            rules: Map<String, Any?>,
        ): Attributed<FormattingResult> {
            calls++
            return Attributed(FormattingResult.Formatted("$code|$version|$rules"), processor)
        }

        override fun check(
            version: String,
            rules: Map<String, Any?>,
        ): Attributed<FormattingCheckResult> {
            calls++
            return Attributed(FormattingCheckResult.Accepted(mapOf("checked" to "$version|$rules")), processor)
        }
    }
}
