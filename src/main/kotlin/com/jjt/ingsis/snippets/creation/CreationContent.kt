package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.metadata.ContentLinkResult
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetMissing
import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StorageUnavailable
import com.jjt.ingsis.snippets.storage.StoreResult
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CreationContent(
    private val storage: SnippetContentStorage,
    private val links: SnippetContentLinks,
) {
    fun store(
        snippetId: UUID,
        code: String,
    ): CreationFailure? =
        try {
            if (links.findContent(snippetId) == null) storeAndLink(snippetId, code) else null
        } catch (_: MetadataPersistenceException) {
            CreationFailure.METADATA_UNAVAILABLE
        }

    private fun storeAndLink(
        snippetId: UUID,
        code: String,
    ): CreationFailure? =
        when (val stored = storage.store(code)) {
            is StoreResult.Stored -> link(snippetId, stored.reference)
            StorageUnavailable -> CreationFailure.STORAGE_UNAVAILABLE
        }

    private fun link(
        snippetId: UUID,
        reference: ContentReference,
    ): CreationFailure? =
        when (val linked = links.linkContent(snippetId, reference)) {
            is ContentLinkResult.Linked -> {
                discard(linked.replaced)
                null
            }

            ContentLinkResult.AlreadyConfirmed -> {
                discard(reference)
                null
            }

            SnippetMissing -> {
                discard(reference)
                CreationFailure.METADATA_UNAVAILABLE
            }
        }

    private fun discard(unused: ContentReference?) {
        if (unused != null) {
            storage.delete(unused)
        }
    }
}
