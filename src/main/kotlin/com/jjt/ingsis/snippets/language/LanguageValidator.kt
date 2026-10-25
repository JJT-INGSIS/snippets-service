package com.jjt.ingsis.snippets.language

interface LanguageValidator {
    val language: String

    val extension: String

    val supportedVersions: Set<String>

    fun validate(
        version: String,
        code: String,
    ): ValidationResult
}
