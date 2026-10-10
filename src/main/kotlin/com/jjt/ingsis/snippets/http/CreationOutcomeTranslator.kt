package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.creation.CreationFailure
import com.jjt.ingsis.snippets.creation.CreationOutcome
import com.jjt.ingsis.snippets.creation.CreationResponse
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

@Component
class CreationOutcomeTranslator(
    private val rejections: RejectionTranslator,
) {
    fun toResponse(outcome: CreationOutcome): ResponseEntity<*> =
        when (outcome) {
            is CreationOutcome.Created -> success(HttpStatus.CREATED, outcome.snippet)
            is CreationOutcome.AlreadyCreated -> success(HttpStatus.OK, outcome.snippet)
            is CreationOutcome.Rejected -> rejections.toResponse(outcome.reason)
            CreationOutcome.Conflict -> SnippetProblem.CREATION_CONFLICT.response()
            is CreationOutcome.Incomplete -> incomplete(outcome.failure)
        }

    private fun success(
        status: HttpStatus,
        snippet: SnippetMetadata,
    ): ResponseEntity<*> {
        val body =
            CreationResponse.fromConfirmed(snippet)
                ?: return SnippetProblem.METADATA_UNAVAILABLE.response()

        return ResponseEntity.status(status).body(body)
    }

    private fun incomplete(failure: CreationFailure): ResponseEntity<*> =
        when (failure) {
            CreationFailure.STORAGE_UNAVAILABLE -> SnippetProblem.STORAGE_UNAVAILABLE.response()
            CreationFailure.PERMISSIONS_UNAVAILABLE -> SnippetProblem.DEPENDENCY_FAILURE.response()
            CreationFailure.OWNER_CONFLICT -> SnippetProblem.OWNERSHIP_CONFLICT.response()
            CreationFailure.METADATA_UNAVAILABLE -> SnippetProblem.METADATA_UNAVAILABLE.response()
        }
}
