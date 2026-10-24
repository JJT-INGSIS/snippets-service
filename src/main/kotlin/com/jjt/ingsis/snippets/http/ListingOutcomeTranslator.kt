package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.listing.ListingOutcome
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

@Component
class ListingOutcomeTranslator {
    fun toResponse(outcome: ListingOutcome): ResponseEntity<*> =
        when (outcome) {
            is ListingOutcome.Listed -> ResponseEntity.ok(SnippetPageResponse.from(outcome.page))
            ListingOutcome.IdentityRejected -> SnippetProblem.IDENTITY_UNAVAILABLE.response()
            ListingOutcome.PermissionsUnavailable -> SnippetProblem.DEPENDENCY_FAILURE.response()
            ListingOutcome.MetadataUnavailable -> SnippetProblem.METADATA_UNAVAILABLE.response()
        }
}
