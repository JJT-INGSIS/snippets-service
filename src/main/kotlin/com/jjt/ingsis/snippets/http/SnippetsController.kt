package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.creation.CreateSnippet
import com.jjt.ingsis.snippets.creation.DraftInput
import com.jjt.ingsis.snippets.creation.DraftJsonDecoder
import com.jjt.ingsis.snippets.update.UpdateSnippet
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/snippets")
class SnippetsController(
    private val decoder: DraftJsonDecoder,
    private val createSnippet: CreateSnippet,
    private val creationTranslator: CreationOutcomeTranslator,
    private val updateSnippet: UpdateSnippet,
    private val updateTranslator: UpdateOutcomeTranslator,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        @RequestBody body: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> =
        when (val input = decoder.decode(body)) {
            is DraftInput.Accepted -> {
                creationTranslator.toResponse(
                    createSnippet.create(input.draft, headers.idempotencyKey(), headers.actorContext()),
                )
            }

            is DraftInput.Rejected -> {
                invalid(input)
            }
        }

    @PutMapping("/{snippetId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun update(
        @PathVariable snippetId: String,
        @RequestBody body: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> =
        when (val input = decoder.decode(body)) {
            is DraftInput.Accepted -> {
                updateTranslator.toResponse(
                    updateSnippet.update(input.draft, snippetId, headers.actorContext()),
                )
            }

            is DraftInput.Rejected -> {
                invalid(input)
            }
        }

    private fun invalid(input: DraftInput.Rejected): ResponseEntity<*> =
        input.field?.let { SnippetProblem.INVALID_REQUEST.response("field", it) }
            ?: SnippetProblem.INVALID_REQUEST.response()
}
