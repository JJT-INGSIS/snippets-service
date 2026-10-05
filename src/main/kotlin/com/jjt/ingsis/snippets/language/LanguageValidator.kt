package com.jjt.ingsis.snippets.language

interface LanguageValidator {
    val language: String

    fun validate(
        version: String,
        code: String,
    ): ValidationResult
}
