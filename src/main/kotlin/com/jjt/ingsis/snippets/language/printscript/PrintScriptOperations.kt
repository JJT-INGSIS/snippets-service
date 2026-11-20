package com.jjt.ingsis.snippets.language.printscript

import com.jjt.ingsis.snippets.background.CapacitySettings
import com.jjt.ingsis.snippets.language.operations.LanguageOperations
import com.jjt.ingsis.snippets.language.operations.ProcessingCommand
import com.jjt.ingsis.snippets.language.operations.ProcessingOperation
import com.jjt.ingsis.snippets.language.operations.ProcessingReply
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClientException
import java.util.UUID

@Component
class PrintScriptOperations(
    properties: PrintScriptProperties,
    private val settings: CapacitySettings,
) : LanguageOperations {
    private val client = printScriptHttpClient(properties)

    override fun start(
        id: UUID,
        command: ProcessingCommand,
    ): ProcessingReply =
        exchange {
            client
                .put()
                .uri(
                    "/operations/{id}",
                    id,
                ).header(
                    "Execution-Active-Limit",
                    settings.activeLimit.toString(),
                ).contentType(MediaType.APPLICATION_JSON)
                .body(command)
                .retrieve()
                .toEntity(ProcessingOperation::class.java)
        }

    override fun find(id: UUID): ProcessingReply =
        exchange {
            client
                .get()
                .uri("/operations/{id}", id)
                .retrieve()
                .toEntity(ProcessingOperation::class.java)
        }

    override fun cancel(id: UUID): ProcessingReply =
        exchange {
            client
                .post()
                .uri("/operations/{id}/cancel", id)
                .retrieve()
                .toEntity(ProcessingOperation::class.java)
        }

    private fun exchange(call: () -> ResponseEntity<ProcessingOperation>): ProcessingReply =
        try {
            val response = call()
            response.body?.let {
                ProcessingReply.Found(
                    it,
                    response.headers.getFirst("Execution-Active-Limit")?.toLongOrNull(),
                )
            }
                ?: ProcessingReply.Unavailable
        } catch (failure: HttpClientErrorException) {
            if (failure.statusCode == HttpStatus.NOT_FOUND) {
                ProcessingReply.Missing
            } else {
                ProcessingReply.Refused("OPERATION_REJECTED_${failure.statusCode.value()}")
            }
        } catch (_: RestClientException) {
            ProcessingReply.Unavailable
        }
}
