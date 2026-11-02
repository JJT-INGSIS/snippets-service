package com.jjt.ingsis.snippets.language.printscript

import com.jjt.ingsis.snippets.language.Attributed
import com.jjt.ingsis.snippets.language.LanguageValidator
import com.jjt.ingsis.snippets.language.PROCESSOR_REVISION_HEADER
import com.jjt.ingsis.snippets.language.ProcessorIdentity
import com.jjt.ingsis.snippets.language.ValidationResult
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.toEntity

@Component
class PrintScriptValidator(
    properties: PrintScriptProperties,
) : LanguageValidator {
    private val logger = LoggerFactory.getLogger(PrintScriptValidator::class.java)

    private val client = printScriptHttpClient(properties)

    override val language: String = "printscript"

    override val extension: String = "prs"

    override val supportedVersions: Set<String> = setOf("1.0", "1.1")

    override fun validate(
        version: String,
        code: String,
    ): ValidationResult = validateAttributed(version, code).result

    override fun validateAttributed(
        version: String,
        code: String,
    ): Attributed<ValidationResult> {
        val request = PrintScriptValidationRequest(code = code, version = version)

        return try {
            val response = send(request)
            Attributed(resultOf(response.body), processorOf(response.headers))
        } catch (rejection: HttpClientErrorException) {
            Attributed(resultOf(rejection, version), processorOf(rejection.responseHeaders))
        } catch (failure: RestClientException) {
            Attributed(failed(failure), ProcessorIdentity.Missing)
        }
    }

    private fun send(request: PrintScriptValidationRequest): ResponseEntity<PrintScriptValidationResponse> =
        client
            .post()
            .uri("/validate")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .toEntity<PrintScriptValidationResponse>()

    private fun processorOf(headers: HttpHeaders?): ProcessorIdentity =
        ProcessorIdentity.of(headers?.getFirst(PROCESSOR_REVISION_HEADER))

    private fun resultOf(response: PrintScriptValidationResponse?): ValidationResult {
        if (response == null) {
            return ValidationResult.Failed("The PrintScript service answered without a body")
        }
        if (response.valid) {
            return ValidationResult.Valid
        }

        return ValidationResult.Invalid(response.diagnostics.map { diagnostic -> diagnostic.toDiagnostic() })
    }

    private fun resultOf(
        rejection: HttpClientErrorException,
        version: String,
    ): ValidationResult {
        if (rejection.statusCode.isSameCodeAs(HttpStatus.UNPROCESSABLE_CONTENT)) {
            return ValidationResult.UnsupportedVersion(language = language, version = version)
        }

        return failed(rejection)
    }

    private fun failed(failure: RestClientException): ValidationResult {
        logger.warn("The PrintScript service could not validate the code", failure)

        return ValidationResult.Failed("The PrintScript service is unavailable or did not answer as its contract says")
    }
}
