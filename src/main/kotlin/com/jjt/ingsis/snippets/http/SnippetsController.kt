package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.creation.CreateSnippet
import com.jjt.ingsis.snippets.creation.DraftInput
import com.jjt.ingsis.snippets.creation.DraftJsonDecoder
import com.jjt.ingsis.snippets.creation.SnippetDraft
import com.jjt.ingsis.snippets.listing.ListSnippets
import com.jjt.ingsis.snippets.update.UpdateSnippet
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/snippets")
class SnippetsController(
    private val decoder: DraftJsonDecoder,
    private val createSnippet: CreateSnippet,
    private val creationTranslator: CreationOutcomeTranslator,
    private val updateSnippet: UpdateSnippet,
    private val updateTranslator: UpdateOutcomeTranslator,
    private val listSnippets: ListSnippets,
    private val listingTranslator: ListingOutcomeTranslator,
) {
    @GetMapping
    fun list(
        @RequestParam parameters: MultiValueMap<String, String>,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> =
        when (val parsed = listingQueryOf(parameters)) {
            is ParsedListing.Invalid -> {
                SnippetProblem.INVALID_REQUEST.response("parameter", parsed.parameter)
            }

            is ParsedListing.Valid -> {
                listingTranslator.toResponse(
                    listSnippets.list(parsed.query, headers.actorContext()),
                )
            }
        }

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        @RequestBody body: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> {
        val draft = draftIn(body) ?: return SnippetProblem.INVALID_REQUEST.response()
        val outcome = createSnippet.create(draft, headers.idempotencyKey(), headers.actorContext())

        return creationTranslator.toResponse(outcome)
    }

    @PutMapping("/{snippetId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun update(
        @PathVariable snippetId: String,
        @RequestBody body: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> {
        val draft = draftIn(body) ?: return SnippetProblem.INVALID_REQUEST.response()
        val outcome = updateSnippet.update(draft, snippetId, headers.actorContext())

        return updateTranslator.toResponse(outcome)
    }

    private fun draftIn(body: String): SnippetDraft? =
        when (val input = decoder.decode(body)) {
            is DraftInput.Accepted -> input.draft
            DraftInput.Rejected -> null
        }
}
