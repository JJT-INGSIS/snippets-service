package com.jjt.ingsis.snippets.language.linting

import com.jjt.ingsis.snippets.language.Attributed
import com.jjt.ingsis.snippets.language.Diagnostic

interface LanguageLinter {
    val language: String

    fun catalog(version: String): Attributed<LintCatalogResult>

    fun check(
        version: String,
        rules: Map<String, Any?>,
    ): Attributed<LintCheckResult>

    fun lint(
        version: String,
        code: String,
        rules: Map<String, Any?>,
    ): Attributed<LintResult>
}

data class LintRule(
    val id: String,
    val type: String,
    val acceptsNull: Boolean,
    val choices: List<String>,
    val conflictsWith: List<String>,
)

data class LintViolation(
    val rule: String,
    val reason: String,
)

sealed interface LintCatalogResult {
    data class Catalog(
        val rules: List<LintRule>,
    ) : LintCatalogResult

    data class UnsupportedLanguage(
        val language: String,
    ) : LintCatalogResult

    data class UnsupportedVersion(
        val language: String,
        val version: String,
    ) : LintCatalogResult

    data class Failed(
        val reason: String,
    ) : LintCatalogResult
}

sealed interface LintCheckResult {
    data class Checked(
        val valid: Boolean,
        val violations: List<LintViolation>,
    ) : LintCheckResult

    data class UnsupportedLanguage(
        val language: String,
    ) : LintCheckResult

    data class UnsupportedVersion(
        val language: String,
        val version: String,
    ) : LintCheckResult

    data class Failed(
        val reason: String,
    ) : LintCheckResult
}

sealed interface LintResult {
    data class Analyzed(
        val diagnostics: List<Diagnostic>,
    ) : LintResult {
        val compliant: Boolean get() = diagnostics.isEmpty()
    }

    data class InvalidCode(
        val diagnostics: List<Diagnostic>,
    ) : LintResult

    data class InvalidRules(
        val violations: List<LintViolation>,
    ) : LintResult

    data object CodeTooLarge : LintResult

    data object DiagnosticsTooLarge : LintResult

    data class UnsupportedLanguage(
        val language: String,
    ) : LintResult

    data class UnsupportedVersion(
        val language: String,
        val version: String,
    ) : LintResult

    data class Failed(
        val reason: String,
    ) : LintResult
}
