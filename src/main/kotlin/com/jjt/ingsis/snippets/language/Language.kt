package com.jjt.ingsis.snippets.language

import org.springframework.stereotype.Service

@Service
class Language(
    private val validators: List<LanguageValidator>,
) {
    init {
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
    ): ValidationResult {
        val validator =
            validators.find { it.language == language }
                ?: return ValidationResult.UnsupportedLanguage(language)
        if (version !in validator.supportedVersions) {
            return ValidationResult.UnsupportedVersion(language, version)
        }

        return validator.validate(version, code)
    }

    fun extensionOf(language: String): String? = validators.find { it.language == language }?.extension
}
