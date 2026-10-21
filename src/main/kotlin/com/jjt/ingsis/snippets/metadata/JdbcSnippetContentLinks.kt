package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.storage.ContentReference
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcSnippetContentLinks(
    private val rows: SnippetContentRows,
    private val transaction: MetadataTransaction,
) : SnippetContentLinks {
    override fun findContent(id: UUID): ContentReference? = transaction.read { rows.findReference(id) }

    override fun linkContent(
        id: UUID,
        reference: ContentReference,
    ): ContentLinkResult = transaction.commit { link(id, reference) }

    override fun replaceContent(
        id: UUID,
        details: SnippetDetails,
        reference: ContentReference,
    ): ContentReplacementResult = transaction.commit { replace(id, details, reference) }

    private fun link(
        id: UUID,
        reference: ContentReference,
    ): ContentLinkResult {
        val current = rows.lock(id) ?: return SnippetMissing
        if (current.state == SnippetState.CONFIRMED) {
            return ContentLinkResult.AlreadyConfirmed
        }

        rows.setReference(id, reference)

        return ContentLinkResult.Linked(replaced = current.reference)
    }

    private fun replace(
        id: UUID,
        details: SnippetDetails,
        reference: ContentReference,
    ): ContentReplacementResult {
        val current = rows.lock(id) ?: return SnippetMissing
        if (current.state == SnippetState.PENDING) {
            return ContentReplacementResult.StillPending
        }

        rows.replace(id, details, reference)

        return ContentReplacementResult.Replaced(
            metadata = confirmed(id, details),
            replaced = current.reference,
        )
    }

    private fun confirmed(
        id: UUID,
        details: SnippetDetails,
    ): SnippetMetadata =
        SnippetMetadata(
            id = id,
            name = details.name,
            description = details.description,
            language = details.language,
            version = details.version,
            state = SnippetState.CONFIRMED,
        )
}
