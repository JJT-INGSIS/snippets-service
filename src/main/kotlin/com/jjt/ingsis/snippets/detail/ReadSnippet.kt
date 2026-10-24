package com.jjt.ingsis.snippets.detail

import com.jjt.ingsis.snippets.creation.canonicalUuid
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.listing.ListedSnippet
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.permissions.AccessChecker
import com.jjt.ingsis.snippets.permissions.AccessLookup
import com.jjt.ingsis.snippets.permissions.SnippetAccess
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ReadSnippet(
    private val identityResolver: ActorIdentityResolver,
    private val links: SnippetContentLinks,
    private val accessChecker: AccessChecker,
    private val content: ContentReading,
    private val language: Language,
) {
    fun read(
        snippetId: String?,
        context: ActorContext,
    ): DetailOutcome {
        val identity = identityResolver.resolve(context)
        if (identity !is ActorIdentity.Identified) return DetailOutcome.IdentityRejected
        val id = canonicalUuid(snippetId) ?: return DetailOutcome.InvalidId

        return try {
            authorized(id, identity.actorId)
        } catch (_: MetadataPersistenceException) {
            DetailOutcome.MetadataUnavailable
        }
    }

    private fun authorized(
        id: UUID,
        actorId: String,
    ): DetailOutcome {
        val linked =
            links.findLinked(id)?.takeIf { it.metadata.state == SnippetState.CONFIRMED }
                ?: return DetailOutcome.Missing

        return when (val access = accessChecker.check(id, actorId)) {
            is AccessLookup.Granted -> detail(linked, access.access)
            AccessLookup.Denied, AccessLookup.Missing -> DetailOutcome.Missing
            is AccessLookup.Failed -> DetailOutcome.PermissionsUnavailable
        }
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
}
