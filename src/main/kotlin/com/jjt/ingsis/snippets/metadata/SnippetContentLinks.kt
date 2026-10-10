package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.storage.ContentReference
import java.util.UUID

interface SnippetContentLinks {
    fun findContent(id: UUID): ContentReference?

    fun linkContent(
        id: UUID,
        reference: ContentReference,
    ): ContentLinkResult

    fun replaceContent(
        id: UUID,
        details: SnippetDetails,
        reference: ContentReference,
    ): ContentReplacementResult
}

sealed interface ContentLinkResult {
    data class Linked(
        val replaced: ContentReference?,
    ) : ContentLinkResult

    data object AlreadyConfirmed : ContentLinkResult
}

sealed interface ContentReplacementResult {
    data class Replaced(
        val metadata: SnippetMetadata,
        val replaced: ContentReference?,
    ) : ContentReplacementResult

    data object StillPending : ContentReplacementResult
}

data object SnippetMissing : ContentLinkResult, ContentReplacementResult
