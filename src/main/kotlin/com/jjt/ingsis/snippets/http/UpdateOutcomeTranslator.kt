package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.creation.CreationResponse
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.update.UpdateFailure
import com.jjt.ingsis.snippets.update.UpdateOutcome
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

@Component
class UpdateOutcomeTranslator(
    private val rejections: RejectionTranslator,
) {
    fun toResponse(outcome: UpdateOutcome): ResponseEntity<*> =
        when (outcome) {
            is UpdateOutcome.Updated -> updated(outcome.snippet)
            is UpdateOutcome.Rejected -> rejections.toResponse(outcome.reason)
            UpdateOutcome.Missing -> SnippetProblem.SNIPPET_NOT_FOUND.response()
            UpdateOutcome.Pending -> SnippetProblem.SNIPPET_PENDING.response()
            UpdateOutcome.Denied -> SnippetProblem.MODIFICATION_DENIED.response()
            UpdateOutcome.OwnershipMissing -> SnippetProblem.OWNERSHIP_MISSING.response()
            is UpdateOutcome.Failed -> failed(outcome.failure)
        }

    private fun updated(snippet: SnippetMetadata): ResponseEntity<*> {
        val body =
            CreationResponse.fromConfirmed(snippet)
                ?: return SnippetProblem.METADATA_UNAVAILABLE.response()

        return ResponseEntity.ok(body)
    }

    private fun failed(failure: UpdateFailure): ResponseEntity<*> =
        when (failure) {
            UpdateFailure.STORAGE_UNAVAILABLE -> SnippetProblem.STORAGE_UNAVAILABLE.response()
            UpdateFailure.METADATA_UNAVAILABLE -> SnippetProblem.METADATA_UNAVAILABLE.response()
        }
}
