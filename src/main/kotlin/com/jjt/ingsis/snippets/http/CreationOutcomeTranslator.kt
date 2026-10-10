package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.creation.CreationFailure
import com.jjt.ingsis.snippets.creation.CreationOutcome
import com.jjt.ingsis.snippets.creation.CreationResponse
import com.jjt.ingsis.snippets.creation.PreparationRejection
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

private const val METADATA = "metadata"

@Component
class CreationOutcomeTranslator {
    fun toResponse(outcome: CreationOutcome): ResponseEntity<*> =
        when (outcome) {
            is CreationOutcome.Created -> success(HttpStatus.CREATED, outcome.snippet)
            is CreationOutcome.AlreadyCreated -> success(HttpStatus.OK, outcome.snippet)
            is CreationOutcome.Rejected -> rejection(outcome.reason)
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

    private fun rejection(reason: PreparationRejection): ResponseEntity<*> =
        when (reason) {
            is PreparationRejection.InvalidRequest -> {
                SnippetProblem.INVALID_REQUEST.response("field", reason.field)
            }

            is PreparationRejection.IdentityRejected -> {
                SnippetProblem.IDENTITY_UNAVAILABLE.response()
            }

            is PreparationRejection.InvalidCode -> {
                SnippetProblem.INVALID_CODE.response("diagnostics", reason.diagnostics)
            }

            is PreparationRejection.UnsupportedLanguage -> {
                SnippetProblem.UNSUPPORTED_LANGUAGE.response()
            }

            is PreparationRejection.UnsupportedVersion -> {
                SnippetProblem.UNSUPPORTED_VERSION.response()
            }

            is PreparationRejection.TechnicalFailure -> {
                technicalFailure(reason.component)
            }
        }

    private fun technicalFailure(component: String): ResponseEntity<*> =
        if (component == METADATA) {
            SnippetProblem.METADATA_UNAVAILABLE.response()
        } else {
            SnippetProblem.DEPENDENCY_FAILURE.response()
        }

    private fun incomplete(failure: CreationFailure): ResponseEntity<*> =
        when (failure) {
            CreationFailure.STORAGE_UNAVAILABLE -> SnippetProblem.STORAGE_UNAVAILABLE.response()
            CreationFailure.PERMISSIONS_UNAVAILABLE -> SnippetProblem.DEPENDENCY_FAILURE.response()
            CreationFailure.OWNER_CONFLICT -> SnippetProblem.OWNERSHIP_CONFLICT.response()
            CreationFailure.METADATA_UNAVAILABLE -> SnippetProblem.METADATA_UNAVAILABLE.response()
        }
}
