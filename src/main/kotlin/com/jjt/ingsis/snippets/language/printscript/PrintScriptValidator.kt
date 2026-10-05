package com.jjt.ingsis.snippets.language.printscript

import com.jjt.ingsis.snippets.language.LanguageValidator
import com.jjt.ingsis.snippets.language.ValidationResult
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body

@Component
class PrintScriptValidator(
    properties: PrintScriptProperties,
) : LanguageValidator {
    private val logger = LoggerFactory.getLogger(PrintScriptValidator::class.java)

    private val client = printScriptHttpClient(properties)

    override val language: String = "printscript"

    override fun validate(
        version: String,
        code: String,
    ): ValidationResult {
        val request = PrintScriptValidationRequest(code = code, version = version)

        return try {
            resultOf(send(request))
        } catch (rejection: HttpClientErrorException) {
            resultOf(rejection, version)
        } catch (failure: RestClientException) {
            failed(failure)
        }
    }

    private fun send(request: PrintScriptValidationRequest): PrintScriptValidationResponse? =
        client
            .post()
            .uri("/validate")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body<PrintScriptValidationResponse>()

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
