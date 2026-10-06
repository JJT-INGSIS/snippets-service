package com.jjt.ingsis.snippets.metadata

import java.util.UUID

interface SnippetMetadataStore {
    fun prepareCreation(request: CreationRequest): CreationResult

    fun findCreation(
        key: UUID,
        actorId: String,
    ): CreationLookup

    fun findConfirmed(id: UUID): SnippetMetadata?

    // Internal read: includes pending metadata and does not publish it as available.
    fun findMetadata(id: UUID): SnippetMetadata?

    // The caller must complete storage and ownership before confirming metadata.
    fun confirmCreation(id: UUID): SnippetMetadata?

    fun updateMetadata(
        id: UUID,
        details: SnippetDetails,
    ): MetadataUpdate
}

sealed interface CreationResult {
    data class Created(
        val metadata: SnippetMetadata,
    ) : CreationResult

    data class Existing(
        val metadata: SnippetMetadata,
    ) : CreationResult

    data object Conflict : CreationResult
}

sealed interface CreationLookup {
    data class Found(
        val metadata: SnippetMetadata,
    ) : CreationLookup

    data object Missing : CreationLookup

    data object IdentityConflict : CreationLookup
}

sealed interface MetadataUpdate {
    data class Updated(
        val metadata: SnippetMetadata,
    ) : MetadataUpdate

    data object Missing : MetadataUpdate

    data object Pending : MetadataUpdate
}

class MetadataPersistenceException(
    cause: Exception,
) : RuntimeException("The snippet metadata operation failed", cause)
