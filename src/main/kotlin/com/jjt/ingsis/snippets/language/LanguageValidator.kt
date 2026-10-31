package com.jjt.ingsis.snippets.language

interface LanguageValidator {
    val language: String

    val extension: String

    val supportedVersions: Set<String>

    fun validate(
        version: String,
        code: String,
    ): ValidationResult

    fun validateAttributed(
        version: String,
        code: String,
    ): Attributed<ValidationResult> = Attributed(validate(version, code), ProcessorIdentity.Missing)
}
