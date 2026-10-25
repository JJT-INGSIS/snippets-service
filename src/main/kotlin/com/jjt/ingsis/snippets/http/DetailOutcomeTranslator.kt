package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.detail.DetailOutcome
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

@Component
class DetailOutcomeTranslator {
    fun toResponse(outcome: DetailOutcome): ResponseEntity<*> =
        when (outcome) {
            is DetailOutcome.Found -> ResponseEntity.ok(SnippetDetailResponse.from(outcome.detail))
            DetailOutcome.InvalidId -> SnippetProblem.INVALID_REQUEST.response("field", "snippetId")
            DetailOutcome.IdentityRejected -> SnippetProblem.IDENTITY_UNAVAILABLE.response()
            DetailOutcome.Missing -> SnippetProblem.SNIPPET_NOT_FOUND.response()
            DetailOutcome.PermissionsUnavailable -> SnippetProblem.DEPENDENCY_FAILURE.response()
            DetailOutcome.MetadataUnavailable -> SnippetProblem.METADATA_UNAVAILABLE.response()
            DetailOutcome.StorageUnavailable -> SnippetProblem.STORAGE_UNAVAILABLE.response()
            DetailOutcome.ContentBroken -> SnippetProblem.TECHNICAL_FAILURE.response()
        }
}
