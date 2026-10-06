package com.jjt.ingsis.snippets.metadata

import java.util.UUID

enum class SnippetState {
    PENDING,
    CONFIRMED,
}

data class SnippetMetadata(
    val id: UUID,
    val name: String,
    val description: String?,
    val language: String,
    val version: String,
    val state: SnippetState,
)

data class SnippetDetails(
    val name: String,
    val description: String?,
    val language: String,
    val version: String,
) {
    init {
        require(name.isNotBlank()) { "The snippet name must not be blank" }
        require(language.isNotBlank()) { "The language must not be blank" }
        require(version.isNotBlank()) { "The language version must not be blank" }
    }
}

data class CreationRequest(
    val key: UUID,
    val actorId: String,
    val fingerprint: String,
    val details: SnippetDetails,
) {
    init {
        require(actorId.isNotBlank()) { "The creation actor must not be blank" }
        require(fingerprint.isNotBlank()) { "The creation fingerprint must not be blank" }
    }
}
