package com.jjt.ingsis.snippets.language

import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.language.execution.LanguageExecutor
import org.springframework.stereotype.Service

@Service
class Language(
    private val validators: List<LanguageValidator>,
    private val executors: List<LanguageExecutor> = emptyList(),
) {
    init {
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
}
