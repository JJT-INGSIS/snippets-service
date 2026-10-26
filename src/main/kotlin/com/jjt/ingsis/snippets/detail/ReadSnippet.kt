package com.jjt.ingsis.snippets.detail

import com.jjt.ingsis.snippets.access.ReadDecision
import com.jjt.ingsis.snippets.access.ReadFailure
import com.jjt.ingsis.snippets.access.ReadGate
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.listing.ListedSnippet
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.permissions.SnippetAccess
import org.springframework.stereotype.Service

@Service
class ReadSnippet(
    private val gate: ReadGate,
    private val content: ContentReading,
    private val language: Language,
) {
    fun read(
        snippetId: String?,
        context: ActorContext,
    ): DetailOutcome =
        when (val decision = gate.open(snippetId, context)) {
            is ReadDecision.Granted -> readContent(decision.snippet, decision.access)
            is ReadDecision.Refused -> outcomeOf(decision.failure)
        }

    private fun readContent(
        linked: LinkedSnippet,
        access: SnippetAccess,
    ): DetailOutcome =
        try {
            detail(linked, access)
        } catch (_: MetadataPersistenceException) {
            DetailOutcome.MetadataUnavailable
        }

    private fun detail(
        linked: LinkedSnippet,
        access: SnippetAccess,
    ): DetailOutcome =
        when (val read = content.read(linked)) {
            is ContentRead.Read -> {
                val metadata = read.snippet.metadata
                val snippet = ListedSnippet(metadata, access, language.extensionOf(metadata.language))
                DetailOutcome.Found(SnippetDetail(snippet, read.code))
            }

            ContentRead.Unavailable -> {
                DetailOutcome.StorageUnavailable
            }

            ContentRead.Broken -> {
                DetailOutcome.ContentBroken
            }
        }

    private fun outcomeOf(failure: ReadFailure): DetailOutcome =
        when (failure) {
            ReadFailure.IDENTITY_REJECTED -> DetailOutcome.IdentityRejected
            ReadFailure.INVALID_ID -> DetailOutcome.InvalidId
            ReadFailure.SNIPPET_MISSING -> DetailOutcome.Missing
            ReadFailure.PERMISSIONS_UNAVAILABLE -> DetailOutcome.PermissionsUnavailable
            ReadFailure.METADATA_UNAVAILABLE -> DetailOutcome.MetadataUnavailable
        }
}
