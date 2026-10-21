package com.jjt.ingsis.snippets.update

import com.jjt.ingsis.snippets.creation.PreparationRejection
import com.jjt.ingsis.snippets.metadata.SnippetMetadata

sealed interface UpdateOutcome {
    data class Updated(
        val snippet: SnippetMetadata,
    ) : UpdateOutcome

    data class Rejected(
        val reason: PreparationRejection,
    ) : UpdateOutcome

    data object Missing : UpdateOutcome

    data object Pending : UpdateOutcome

    data object Denied : UpdateOutcome

    data object OwnershipMissing : UpdateOutcome

    data class Failed(
        val failure: UpdateFailure,
    ) : UpdateOutcome
}

enum class UpdateFailure {
    STORAGE_UNAVAILABLE,
    METADATA_UNAVAILABLE,
}
