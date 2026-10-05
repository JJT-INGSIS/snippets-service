package com.jjt.ingsis.snippets.language

sealed interface ValidationResult {
    data object Valid : ValidationResult

    data class Invalid(
        val diagnostics: List<Diagnostic>,
    ) : ValidationResult

    data class UnsupportedLanguage(
        val language: String,
    ) : ValidationResult

    data class UnsupportedVersion(
        val language: String,
        val version: String,
        val supportedVersions: List<String>,
    ) : ValidationResult

    data class Failed(
        val reason: String,
    ) : ValidationResult
}
