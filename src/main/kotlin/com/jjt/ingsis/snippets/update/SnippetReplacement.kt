package com.jjt.ingsis.snippets.update

import com.jjt.ingsis.snippets.metadata.ContentReplacementResult
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetMissing
import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StorageUnavailable
import com.jjt.ingsis.snippets.storage.StoreResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class SnippetReplacement(
    private val storage: SnippetContentStorage,
    private val links: SnippetContentLinks,
) {
    private val logger = LoggerFactory.getLogger(SnippetReplacement::class.java)

    fun apply(candidate: UpdateCandidate): UpdateOutcome =
        when (val stored = storage.store(candidate.replacement.code)) {
            is StoreResult.Stored -> replace(candidate, stored.reference)
            StorageUnavailable -> failed(candidate, UpdateFailure.STORAGE_UNAVAILABLE)
        }

    private fun replace(
        candidate: UpdateCandidate,
        reference: ContentReference,
    ): UpdateOutcome =
        try {
            val details = candidate.replacement.details()

            when (val result = links.replaceContent(candidate.snippetId, details, reference)) {
                is ContentReplacementResult.Replaced -> {
                    discard(result.replaced)
                    UpdateOutcome.Updated(result.metadata)
                }

                ContentReplacementResult.StillPending -> {
                    discard(reference)
                    UpdateOutcome.Pending
                }

                SnippetMissing -> {
                    discard(reference)
                    UpdateOutcome.Missing
                }
            }
        } catch (_: MetadataPersistenceException) {
            failed(candidate, UpdateFailure.METADATA_UNAVAILABLE)
        }

    private fun discard(unused: ContentReference?) {
        if (unused != null) {
            storage.delete(unused)
        }
    }

    private fun failed(
        candidate: UpdateCandidate,
        failure: UpdateFailure,
    ): UpdateOutcome {
        logger.warn("The update of snippet {} did not complete: {}", candidate.snippetId, failure)

        return UpdateOutcome.Failed(failure)
    }
}
