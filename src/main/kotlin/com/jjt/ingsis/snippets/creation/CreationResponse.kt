package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetState
import java.util.UUID

data class CreationResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val language: String,
    val version: String,
) {
    companion object {
        fun fromConfirmed(metadata: SnippetMetadata): CreationResponse? =
            if (metadata.state == SnippetState.CONFIRMED) {
                CreationResponse(
                    id = metadata.id,
                    name = metadata.name,
                    description = metadata.description,
                    language = metadata.language,
                    version = metadata.version,
                )
            } else {
                null
            }
    }
}
