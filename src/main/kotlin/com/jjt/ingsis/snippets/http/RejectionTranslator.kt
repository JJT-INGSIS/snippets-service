package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.creation.PreparationRejection
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

private const val METADATA = "metadata"

@Component
class RejectionTranslator {
    fun toResponse(reason: PreparationRejection): ResponseEntity<*> =
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
}
