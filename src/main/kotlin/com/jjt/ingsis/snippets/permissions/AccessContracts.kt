package com.jjt.ingsis.snippets.permissions

import java.util.UUID

enum class AccessRelation {
    OWNED,
    SHARED,
}

enum class RelationFilter {
    ALL,
    OWNED,
    SHARED,
}

data class SnippetAccess(
    val snippetId: UUID,
    val ownerId: String,
    val relation: AccessRelation,
)

sealed interface AccessListing {
    data class Listed(
        val accesses: List<SnippetAccess>,
    ) : AccessListing

    data class Failed(
        val reason: PermissionsFailure,
    ) : AccessListing
}

sealed interface AccessLookup {
    data class Granted(
        val access: SnippetAccess,
    ) : AccessLookup

    data object Denied : AccessLookup

    data object Missing : AccessLookup

    data class Failed(
        val reason: PermissionsFailure,
    ) : AccessLookup
}

fun interface AccessChecker {
    fun check(
        snippetId: UUID,
        actorId: String,
    ): AccessLookup
}

fun interface AccessLister {
    fun list(
        actorId: String,
        filter: RelationFilter,
    ): AccessListing
}
