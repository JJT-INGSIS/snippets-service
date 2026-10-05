package com.jjt.ingsis.snippets.language

import org.springframework.stereotype.Service

@Service
class Language(
    private val validators: List<LanguageValidator>,
) {
    fun validate(
        language: String,
        version: String,
        code: String,
    ): ValidationResult {
        for (validator in validators) {
            if (validator.language == language) {
                return validator.validate(version, code)
            }
        }

        return ValidationResult.UnsupportedLanguage(language)
    }
}
