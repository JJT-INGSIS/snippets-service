package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.newrelic.api.agent.Trace
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CreationConfirmation(
    private val metadata: SnippetMetadataStore,
) {
    @Trace(metricName = "Snippets/creation/confirm")
    fun confirm(snippetId: UUID): SnippetMetadata? =
        try {
            metadata.confirmCreation(snippetId)
        } catch (_: MetadataPersistenceException) {
            null
        }
}
