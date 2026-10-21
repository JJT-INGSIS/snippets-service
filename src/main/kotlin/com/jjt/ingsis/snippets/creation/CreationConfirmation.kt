package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CreationConfirmation(
    private val metadata: SnippetMetadataStore,
) {
    fun confirm(snippetId: UUID): SnippetMetadata? =
        try {
            metadata.confirmCreation(snippetId)
        } catch (_: MetadataPersistenceException) {
            null
        }
}
