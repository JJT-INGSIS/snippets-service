package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.metadata.SnippetMetadata

enum class VariantSource { STORED, ON_DEMAND }

sealed interface FormattedDownload {
    data class Ready(
        val snippet: SnippetMetadata,
        val extension: String,
        val code: String,
        val basis: FormattingBasis,
        val source: VariantSource,
    ) : FormattedDownload

    data class NotReady(
        val reason: SavedSnippetFormatting,
    ) : FormattedDownload
}
