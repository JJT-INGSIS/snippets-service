package com.jjt.ingsis.snippets.listing

import com.jjt.ingsis.snippets.metadata.PageRequest
import com.jjt.ingsis.snippets.metadata.SnippetFilter
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetOrder
import com.jjt.ingsis.snippets.permissions.RelationFilter
import com.jjt.ingsis.snippets.permissions.SnippetAccess

data class ListingQuery(
    val relation: RelationFilter,
    val filter: SnippetFilter,
    val order: SnippetOrder,
    val page: PageRequest,
)

data class ListedSnippet(
    val metadata: SnippetMetadata,
    val access: SnippetAccess,
    val extension: String?,
)

data class SnippetPage(
    val page: PageRequest,
    val count: Long,
    val snippets: List<ListedSnippet>,
)

sealed interface ListingOutcome {
    data class Listed(
        val page: SnippetPage,
    ) : ListingOutcome

    data object IdentityRejected : ListingOutcome

    data object PermissionsUnavailable : ListingOutcome

    data object MetadataUnavailable : ListingOutcome
}
