package com.jjt.ingsis.snippets.language.formatting

import com.jjt.ingsis.snippets.language.Attributed
import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.language.ProcessorIdentity

interface LanguageFormatter {
    val language: String

    fun processor(): ProcessorIdentity = ProcessorIdentity.Missing

    fun catalog(version: String): Attributed<FormattingCatalogResult>

    fun format(
        version: String,
        code: String,
        rules: Map<String, Any?>,
    ): Attributed<FormattingResult>

    fun check(
        version: String,
        rules: Map<String, Any?>,
    ): Attributed<FormattingCheckResult>
}

data class FormattingRule(
    val id: String,
    val type: String,
    val default: Boolean?,
    val minimum: Int?,
    val maximum: Int?,
    val conflictsWith: List<String>,
    val description: String,
)

data class FormattingRuleViolation(
    val rule: String,
    val reason: String,
)

sealed interface FormattingCatalogResult {
    data class Catalog(
        val rules: List<FormattingRule>,
    ) : FormattingCatalogResult

    data class UnsupportedLanguage(
        val language: String,
    ) : FormattingCatalogResult

    data class UnsupportedVersion(
        val language: String,
        val version: String,
    ) : FormattingCatalogResult

    data class Failed(
        val reason: String,
    ) : FormattingCatalogResult
}

sealed interface FormattingResult {
    data class Formatted(
        val code: String,
    ) : FormattingResult

    data class Unformattable(
        val diagnostics: List<Diagnostic>,
    ) : FormattingResult

    data class InvalidRules(
        val violations: List<FormattingRuleViolation>,
    ) : FormattingResult

    data object CodeTooLarge : FormattingResult

    data object OutputTooLarge : FormattingResult

    data class UnsupportedLanguage(
        val language: String,
    ) : FormattingResult

    data class UnsupportedVersion(
        val language: String,
        val version: String,
    ) : FormattingResult

    data class Failed(
        val reason: String,
    ) : FormattingResult
}

sealed interface FormattingCheckResult {
    data class Accepted(
        val selection: Map<String, Any>,
    ) : FormattingCheckResult

    data class InvalidRules(
        val violations: List<FormattingRuleViolation>,
    ) : FormattingCheckResult

    data class UnsupportedLanguage(
        val language: String,
    ) : FormattingCheckResult

    data class UnsupportedVersion(
        val language: String,
        val version: String,
    ) : FormattingCheckResult

    data class Failed(
        val reason: String,
    ) : FormattingCheckResult
}
