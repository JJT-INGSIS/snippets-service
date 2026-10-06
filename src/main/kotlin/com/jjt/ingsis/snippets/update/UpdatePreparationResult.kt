package com.jjt.ingsis.snippets.update

import com.jjt.ingsis.snippets.creation.PreparationRejection
import com.jjt.ingsis.snippets.creation.SnippetDraft
import java.util.UUID

data class UpdateCandidate(
    val snippetId: UUID,
    val replacement: SnippetDraft,
)

sealed interface UpdatePreparationResult {
    // A validated candidate, never a successful write or public update response.
    data class Prepared(
        val candidate: UpdateCandidate,
    ) : UpdatePreparationResult

    data class Rejected(
        val reason: PreparationRejection,
    ) : UpdatePreparationResult

    data object Missing : UpdatePreparationResult

    data object Pending : UpdatePreparationResult

    data object Denied : UpdatePreparationResult

    data object OwnershipMissing : UpdatePreparationResult
}
