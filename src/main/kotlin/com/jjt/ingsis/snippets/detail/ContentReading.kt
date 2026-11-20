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

private const val MAX_SNAPSHOT_ATTEMPTS = 3

sealed interface StableContentRead {
    data class Read(
        val snippet: LinkedSnippet,
        val code: String,
    ) : StableContentRead

    data object Conflict : StableContentRead

    data object Unavailable : StableContentRead

    data object Broken : StableContentRead
}

@Component
class ContentReading(
    private val links: SnippetContentLinks,
    private val storage: SnippetContentStorage,
) {
    // Execution needs a verified reference/language/version tuple after the blob read.
    // No database transaction remains open while calling storage or the interpreter.
    fun readStable(snippet: LinkedSnippet): StableContentRead = stable(snippet, MAX_SNAPSHOT_ATTEMPTS)

    private fun stable(
        snippet: LinkedSnippet,
        attempts: Int,
    ): StableContentRead {
        val reference = snippet.reference ?: return StableContentRead.Broken
        val read = storage.read(reference)
        if (read == StorageUnavailable) return StableContentRead.Unavailable
        if (read == InvalidContentReference) return StableContentRead.Broken
        val current = links.findLinked(snippet.metadata.id)
        if (current == null || current.metadata.state != SnippetState.CONFIRMED) return StableContentRead.Conflict
        val same =
            current.reference == reference && current.metadata.language == snippet.metadata.language &&
                current.metadata.version == snippet.metadata.version
        return when {
            !same && attempts > 1 -> stable(current, attempts - 1)
            !same -> StableContentRead.Conflict
            read is ReadResult.Found -> StableContentRead.Read(snippet, read.code)
            else -> StableContentRead.Broken
        }
    }

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
