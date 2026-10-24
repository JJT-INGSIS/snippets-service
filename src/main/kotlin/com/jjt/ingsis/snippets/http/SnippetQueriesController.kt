package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.detail.ReadSnippet
import com.jjt.ingsis.snippets.listing.ListSnippets
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/snippets")
class SnippetQueriesController(
    private val listSnippets: ListSnippets,
    private val listingTranslator: ListingOutcomeTranslator,
    private val readSnippet: ReadSnippet,
    private val detailTranslator: DetailOutcomeTranslator,
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
                listingTranslator.toResponse(listSnippets.list(parsed.query, headers.actorContext()))
            }
        }

    @GetMapping("/{snippetId}")
    fun detail(
        @PathVariable snippetId: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> = detailTranslator.toResponse(readSnippet.read(snippetId, headers.actorContext()))
}
