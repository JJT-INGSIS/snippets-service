package com.jjt.ingsis.snippets.listing

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetSearch
import com.jjt.ingsis.snippets.permissions.AccessLister
import com.jjt.ingsis.snippets.permissions.AccessListing
import com.jjt.ingsis.snippets.permissions.SnippetAccess
import org.springframework.stereotype.Service

@Service
class ListSnippets(
    private val identityResolver: ActorIdentityResolver,
    private val accessLister: AccessLister,
    private val search: SnippetSearch,
    private val language: Language,
) {
    fun list(
        query: ListingQuery,
        context: ActorContext,
    ): ListingOutcome {
        val identity = identityResolver.resolve(context)
        if (identity !is ActorIdentity.Identified) return ListingOutcome.IdentityRejected

        return when (val listing = accessLister.list(identity.actorId, query.relation)) {
            is AccessListing.Listed -> page(query, listing.accesses)
            is AccessListing.Failed -> ListingOutcome.PermissionsUnavailable
        }
    }

    private fun page(
        query: ListingQuery,
        accesses: List<SnippetAccess>,
    ): ListingOutcome {
        val accessById = accesses.associateBy { it.snippetId }
        val found =
            try {
                search.search(ids = accessById.keys, filter = query.filter, order = query.order, page = query.page)
            } catch (_: MetadataPersistenceException) {
                return ListingOutcome.MetadataUnavailable
            }
        val snippets =
            found.snippets.map { metadata ->
                ListedSnippet(metadata, accessById.getValue(metadata.id), language.extensionOf(metadata.language))
            }

        return ListingOutcome.Listed(SnippetPage(query.page, found.count, snippets))
    }
}
