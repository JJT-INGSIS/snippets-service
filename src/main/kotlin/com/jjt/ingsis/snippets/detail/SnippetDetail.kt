package com.jjt.ingsis.snippets.detail

import com.jjt.ingsis.snippets.listing.ListedSnippet
import com.jjt.ingsis.snippets.permissions.AccessRelation

data class Capabilities(
    val modify: Boolean,
    val share: Boolean,
) {
    companion object {
        fun of(relation: AccessRelation): Capabilities {
            val owner = relation == AccessRelation.OWNED

            return Capabilities(modify = owner, share = owner)
        }
    }
}

data class SnippetDetail(
    val snippet: ListedSnippet,
    val code: String,
) {
    val capabilities: Capabilities
        get() = Capabilities.of(snippet.access.relation)
}

sealed interface DetailOutcome {
    data class Found(
        val detail: SnippetDetail,
    ) : DetailOutcome

    data object InvalidId : DetailOutcome

    data object IdentityRejected : DetailOutcome

    data object Missing : DetailOutcome

    data object PermissionsUnavailable : DetailOutcome

    data object MetadataUnavailable : DetailOutcome

    data object StorageUnavailable : DetailOutcome

    data object ContentBroken : DetailOutcome
}
