package com.jjt.ingsis.snippets.language.printscript

import com.jjt.ingsis.snippets.language.Attributed
import com.jjt.ingsis.snippets.language.PROCESSOR_REVISION_HEADER
import com.jjt.ingsis.snippets.language.ProcessorIdentity
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClientException

internal class PrintScriptExchange(
    properties: PrintScriptProperties,
) {
    private val logger = LoggerFactory.getLogger(PrintScriptExchange::class.java)

    private val client = printScriptHttpClient(properties)

    fun <T : Any> get(
        uri: String,
        variable: String,
        answer: Class<T>,
    ): Attributed<PrintScriptAnswer<T>> =
        exchange {
            client
                .get()
                .uri(uri, variable)
                .retrieve()
                .toEntity(answer)
        }

    fun <T : Any> post(
        uri: String,
        body: Any,
        answer: Class<T>,
    ): Attributed<PrintScriptAnswer<T>> =
        exchange {
            client
                .post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(answer)
        }

    private fun <T : Any> exchange(call: () -> ResponseEntity<T>): Attributed<PrintScriptAnswer<T>> =
        try {
            val response = call()
            Attributed(PrintScriptAnswer.Answered(response.body), processorOf(response.headers))
        } catch (rejection: HttpClientErrorException) {
            val rejected = PrintScriptAnswer.Rejected(rejection.statusCode.value(), problemOf(rejection))
            Attributed(rejected, processorOf(rejection.responseHeaders))
        } catch (failure: RestClientException) {
            logger.warn("The PrintScript service is unavailable or did not answer as its contract says", failure)
            Attributed(PrintScriptAnswer.Unavailable, ProcessorIdentity.Missing)
        }

    private fun problemOf(rejection: HttpClientErrorException): PrintScriptProblem =
        try {
            rejection.getResponseBodyAs(PrintScriptProblem::class.java) ?: PrintScriptProblem()
        } catch (unreadable: RestClientException) {
            logger.debug("The PrintScript service rejected the request with an unreadable body", unreadable)
            PrintScriptProblem()
        }

    private fun processorOf(headers: HttpHeaders?): ProcessorIdentity =
        ProcessorIdentity.of(headers?.getFirst(PROCESSOR_REVISION_HEADER))
}
