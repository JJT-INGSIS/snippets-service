package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.metadata.SnippetMetadata

sealed interface CreationOutcome {
    data class Created(
        val snippet: SnippetMetadata,
    ) : CreationOutcome

    data class AlreadyCreated(
        val snippet: SnippetMetadata,
    ) : CreationOutcome

    data class Rejected(
        val reason: PreparationRejection,
    ) : CreationOutcome

    data object Conflict : CreationOutcome

    data class Incomplete(
        val failure: CreationFailure,
    ) : CreationOutcome
}

enum class CreationFailure {
    STORAGE_UNAVAILABLE,
    PERMISSIONS_UNAVAILABLE,
    OWNER_CONFLICT,
    METADATA_UNAVAILABLE,
}
