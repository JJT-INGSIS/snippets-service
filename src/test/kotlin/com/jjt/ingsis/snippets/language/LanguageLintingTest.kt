package com.jjt.ingsis.snippets.language

import com.jjt.ingsis.snippets.language.linting.LintCatalogResult
import com.jjt.ingsis.snippets.language.linting.LintCheckResult
import com.jjt.ingsis.snippets.language.linting.LintResult
import com.jjt.ingsis.snippets.language.printscript.PrintScriptLinter
import com.jjt.ingsis.snippets.language.printscript.PrintScriptProperties
import com.jjt.ingsis.snippets.language.printscript.StubLanguageService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class LanguageLintingTest {
    private val validator =
        object : LanguageValidator {
            override val language = "printscript"
            override val extension = "ps"
            override val supportedVersions = setOf("1.0", "1.1")

            override fun validate(
                version: String,
                code: String,
            ): ValidationResult = ValidationResult.Valid
        }

    @Test
    fun `lint dispatches by language and preserves empty rules through the adapter`() {
        StubLanguageService(200, """{"analyzed":true,"compliant":true,"diagnostics":[]}""").use {
            val language =
                Language(
                    listOf(validator),
                    linters = listOf(PrintScriptLinter(PrintScriptProperties(baseUrl = it.baseUrl))),
                )
            assertThat(
                language
                    .lint(
                        language = "printscript",
                        version = "1.0",
                        code = "println(1+2);",
                        rules = emptyMap(),
                    ).result,
            ).isEqualTo(LintResult.Analyzed(emptyList()))
            assertThat(it.received.single().body).contains("\"rules\":{}")
        }
    }

    @Test
    fun `unsupported language and version are rejected without calling the provider`() {
        val linter = PrintScriptLinter(PrintScriptProperties(baseUrl = "http://127.0.0.1:9"))
        val language = Language(listOf(validator), linters = listOf(linter))
        assertThat(language.lint(language = "python", version = "3", code = "code", rules = emptyMap()).result)
            .isEqualTo(LintResult.UnsupportedLanguage("python"))
        assertThat(language.lint(language = "printscript", version = "9.9", code = "code", rules = emptyMap()).result)
            .isEqualTo(LintResult.UnsupportedVersion("printscript", "9.9"))
        assertThat(language.lintingCatalog("printscript", "9.9").result)
            .isInstanceOf(LintCatalogResult.UnsupportedVersion::class.java)
        assertThat(language.checkLintingRules("printscript", "9.9", emptyMap()).result)
            .isInstanceOf(LintCheckResult.UnsupportedVersion::class.java)
        assertThatIllegalArgumentException().isThrownBy {
            Language(listOf(validator), linters = listOf(linter, linter))
        }
    }
}
