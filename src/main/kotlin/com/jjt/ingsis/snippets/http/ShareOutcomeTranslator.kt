package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.sharing.ShareOutcome
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import java.util.UUID

data class ShareResponse(
    val snippetId: UUID,
    val userId: String,
)

@Component
class ShareOutcomeTranslator {
    fun toResponse(outcome: ShareOutcome): ResponseEntity<*> =
        when (outcome) {
            is ShareOutcome.Shared -> {
                ResponseEntity
                    .status(
                        if (outcome.created) HttpStatus.CREATED else HttpStatus.OK,
                    ).body(ShareResponse(outcome.snippetId, outcome.userId))
            }

            is ShareOutcome.InvalidRequest -> {
                SnippetProblem.INVALID_REQUEST.response("field", outcome.field)
            }

            ShareOutcome.IdentityRejected -> {
                SnippetProblem.IDENTITY_UNAVAILABLE.response()
            }

            ShareOutcome.InvalidRecipient -> {
                SnippetProblem.INVALID_RECIPIENT.response()
            }

            ShareOutcome.Missing -> {
                SnippetProblem.SNIPPET_NOT_FOUND.response()
            }

            ShareOutcome.Denied -> {
                SnippetProblem.SHARE_DENIED.response()
            }

            ShareOutcome.MetadataUnavailable -> {
                SnippetProblem.METADATA_UNAVAILABLE.response()
            }

            ShareOutcome.PermissionsUnavailable -> {
                SnippetProblem.DEPENDENCY_FAILURE.response()
            }
        }
}
