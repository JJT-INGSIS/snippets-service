package com.jjt.ingsis.snippets.access

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.permissions.SnippetAccess

enum class ReadFailure {
    IDENTITY_REJECTED,
    INVALID_ID,
    SNIPPET_MISSING,
    PERMISSIONS_UNAVAILABLE,
    METADATA_UNAVAILABLE,
}

sealed interface ReadDecision {
    data class Granted(
        val snippet: LinkedSnippet,
        val access: SnippetAccess,
    ) : ReadDecision

    data class Refused(
        val failure: ReadFailure,
    ) : ReadDecision
}

fun interface ReadGate {
    fun open(
        snippetId: String?,
        context: ActorContext,
    ): ReadDecision
}
