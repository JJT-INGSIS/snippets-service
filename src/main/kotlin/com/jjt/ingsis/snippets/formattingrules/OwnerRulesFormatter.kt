package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.detail.ContentReading
import com.jjt.ingsis.snippets.detail.StableContentRead
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.formatting.FormattingResult
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import org.springframework.stereotype.Component

@Component
class OwnerRulesFormatter(
    private val content: ContentReading,
    private val rules: ApplicableFormattingRules,
    private val language: Language,
) {
    fun format(
        snippet: LinkedSnippet,
        ownerId: String,
    ): SavedSnippetFormatting =
        when (val read = content.readStable(snippet)) {
            is StableContentRead.Read -> formatRead(read, ownerId)
            StableContentRead.Conflict -> SavedSnippetFormatting.Stale
            StableContentRead.Unavailable -> SavedSnippetFormatting.StorageUnavailable
            StableContentRead.Broken -> SavedSnippetFormatting.ContentBroken
        }

    private fun formatRead(
        read: StableContentRead.Read,
        ownerId: String,
    ): SavedSnippetFormatting {
        val metadata = read.snippet.metadata
        val selection = rules.of(metadata, ownerId)
        val formatted =
            language.format(
                language = metadata.language,
                version = metadata.version,
                code = read.code,
                rules = selection.rules,
            )
        val result =
            formatted.result as? FormattingResult.Formatted
                ?: return SavedSnippetFormatting.notFormatted(formatted.result, metadata)
        val processor = formatted.processor.verifiedOrNull() ?: return SavedSnippetFormatting.LanguageUnavailable
        val basis =
            FormattingBasis.of(read.snippet, selection, processor) ?: return SavedSnippetFormatting.ContentBroken

        return SavedSnippetFormatting.Formatted(metadata, result.code, basis)
    }
}
