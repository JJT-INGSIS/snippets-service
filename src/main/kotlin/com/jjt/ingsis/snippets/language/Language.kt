package com.jjt.ingsis.snippets.language

import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.language.execution.LanguageExecutor
import com.jjt.ingsis.snippets.language.formatting.FormattingCatalogResult
import com.jjt.ingsis.snippets.language.formatting.FormattingCheckResult
import com.jjt.ingsis.snippets.language.formatting.FormattingResult
import com.jjt.ingsis.snippets.language.formatting.LanguageFormatter
import com.jjt.ingsis.snippets.language.linting.LanguageLinter
import com.jjt.ingsis.snippets.language.linting.LintCatalogResult
import com.jjt.ingsis.snippets.language.linting.LintCheckResult
import com.jjt.ingsis.snippets.language.linting.LintResult
import org.springframework.stereotype.Service

@Service
// Keep capability selection in the existing facade rather than adding another registry.
@Suppress("TooManyFunctions")
class Language(
    private val validators: List<LanguageValidator>,
    private val executors: List<LanguageExecutor> = emptyList(),
    private val formatters: List<LanguageFormatter> = emptyList(),
    private val linters: List<LanguageLinter> = emptyList(),
) {
    init {
        require(linters.map { it.language }.distinct().size == linters.size) { "Duplicate linter capability" }
        require(formatters.map { it.language }.distinct().size == formatters.size) {
            "Each language must have exactly one formatter"
        }
        require(executors.map { it.language }.distinct().size == executors.size) {
            "Each language must have exactly one execution adapter"
        }
        require(validators.map { it.language }.distinct().size == validators.size) {
            "Each language must have exactly one validator"
        }
        validators.forEach { validator ->
            require(validator.language.isNotBlank() && validator.language == validator.language.trim()) {
                "Language identifiers must be nonblank and trimmed"
            }
            require(validator.extension.matches(Regex("[A-Za-z0-9]+"))) {
                "File extensions must be alphanumeric, without a dot"
            }
            require(
                validator.supportedVersions.isNotEmpty() &&
                    validator.supportedVersions.all { it.isNotBlank() && it == it.trim() },
            ) {
                "Each language must declare nonblank, trimmed versions"
            }
        }
    }

    fun supportedLanguages(): List<SupportedLanguage> =
        validators.sortedBy { it.language }.map { validator ->
            SupportedLanguage(validator.language, validator.extension, validator.supportedVersions.sorted())
        }

    fun validate(
        language: String,
        version: String,
        code: String,
    ): ValidationResult = validateAttributed(language, version, code).result

    fun validationProcessor(language: String): ProcessorIdentity =
        validators.find { it.language == language }?.validationProcessor() ?: ProcessorIdentity.Missing

    fun validateAttributed(
        language: String,
        version: String,
        code: String,
    ): Attributed<ValidationResult> {
        val validator =
            validators.find { it.language == language }
                ?: return Attributed(ValidationResult.UnsupportedLanguage(language), ProcessorIdentity.Missing)
        if (version !in validator.supportedVersions) {
            return Attributed(ValidationResult.UnsupportedVersion(language, version), ProcessorIdentity.Missing)
        }

        return validator.validateAttributed(version, code)
    }

    fun extensionOf(language: String): String? = validators.find { it.language == language }?.extension

    fun executorFor(language: String): ExecutionResult<LanguageExecutor> =
        executors.find { it.language == language }?.let { ExecutionResult.Success(it) }
            ?: ExecutionResult.Rejected("unsupported-execution-capability")

    fun formattingCatalog(
        language: String,
        version: String,
    ): Attributed<FormattingCatalogResult> {
        val formatter =
            formatters.find { it.language == language }
                ?: return Attributed(FormattingCatalogResult.UnsupportedLanguage(language), ProcessorIdentity.Missing)
        if (!supports(language, version)) {
            return Attributed(FormattingCatalogResult.UnsupportedVersion(language, version), ProcessorIdentity.Missing)
        }

        return formatter.catalog(version)
    }

    fun format(
        language: String,
        version: String,
        code: String,
        rules: Map<String, Any?>,
    ): Attributed<FormattingResult> {
        val formatter =
            formatters.find { it.language == language }
                ?: return Attributed(FormattingResult.UnsupportedLanguage(language), ProcessorIdentity.Missing)
        if (!supports(language, version)) {
            return Attributed(FormattingResult.UnsupportedVersion(language, version), ProcessorIdentity.Missing)
        }

        return formatter.format(version, code, rules)
    }

    fun checkFormattingRules(
        language: String,
        version: String,
        rules: Map<String, Any?>,
    ): Attributed<FormattingCheckResult> {
        val formatter =
            formatters.find { it.language == language }
                ?: return Attributed(FormattingCheckResult.UnsupportedLanguage(language), ProcessorIdentity.Missing)
        if (!supports(language, version)) {
            return Attributed(FormattingCheckResult.UnsupportedVersion(language, version), ProcessorIdentity.Missing)
        }

        return formatter.check(version, rules)
    }

    fun lintingCatalog(
        language: String,
        version: String,
    ): Attributed<LintCatalogResult> {
        val linter =
            linters.find { it.language == language }
                ?: return Attributed(LintCatalogResult.UnsupportedLanguage(language), ProcessorIdentity.Missing)
        if (!supports(language, version)) {
            return Attributed(LintCatalogResult.UnsupportedVersion(language, version), ProcessorIdentity.Missing)
        }
        return linter.catalog(version)
    }

    fun checkLintingRules(
        language: String,
        version: String,
        rules: Map<String, Any?>,
    ): Attributed<LintCheckResult> {
        val linter =
            linters.find { it.language == language }
                ?: return Attributed(LintCheckResult.UnsupportedLanguage(language), ProcessorIdentity.Missing)
        if (!supports(language, version)) {
            return Attributed(LintCheckResult.UnsupportedVersion(language, version), ProcessorIdentity.Missing)
        }
        return linter.check(version, rules)
    }

    fun lint(
        language: String,
        version: String,
        code: String,
        rules: Map<String, Any?>,
    ): Attributed<LintResult> {
        val linter =
            linters.find { it.language == language }
                ?: return Attributed(LintResult.UnsupportedLanguage(language), ProcessorIdentity.Missing)
        if (!supports(language, version)) {
            return Attributed(LintResult.UnsupportedVersion(language, version), ProcessorIdentity.Missing)
        }
        return linter.lint(version, code, rules)
    }

    private fun supports(
        language: String,
        version: String,
    ): Boolean = validators.find { it.language == language }?.supportedVersions?.contains(version) == true
}
