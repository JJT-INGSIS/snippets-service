package com.jjt.ingsis.snippets.detail

import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.InvalidContentReference
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StorageUnavailable
import org.springframework.stereotype.Component

sealed interface ContentRead {
    data class Read(
        val snippet: LinkedSnippet,
        val code: String,
    ) : ContentRead

    data object Unavailable : ContentRead

    data object Broken : ContentRead
}

@Component
class ContentReading(
    private val links: SnippetContentLinks,
    private val storage: SnippetContentStorage,
) {
    fun read(snippet: LinkedSnippet): ContentRead {
        val reference = snippet.reference ?: return ContentRead.Broken

        return readOr(snippet, reference) { reread(snippet) }
    }

    private fun reread(first: LinkedSnippet): ContentRead {
        val current = links.findLinked(first.metadata.id)?.takeIf { it.metadata.state == SnippetState.CONFIRMED }
        val reference = current?.reference?.takeIf { it != first.reference }

        return if (current != null && reference != null) {
            readOr(current, reference) { ContentRead.Unavailable }
        } else {
            ContentRead.Unavailable
        }
    }

    private fun readOr(
        snippet: LinkedSnippet,
        reference: ContentReference,
        whenMissing: () -> ContentRead,
    ): ContentRead =
        when (val result = storage.read(reference)) {
            is ReadResult.Found -> ContentRead.Read(snippet, result.code)
            ReadResult.Missing -> whenMissing()
            StorageUnavailable -> ContentRead.Unavailable
            InvalidContentReference -> ContentRead.Broken
        }
}
