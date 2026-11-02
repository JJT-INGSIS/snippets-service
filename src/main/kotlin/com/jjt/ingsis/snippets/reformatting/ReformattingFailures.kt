package com.jjt.ingsis.snippets.reformatting

import com.jjt.ingsis.snippets.formattingrules.SavedSnippetFormatting

data class ReformattingFailure(
    val reason: String,
    val definitive: Boolean,
)

internal object ReformattingFailures {
    fun of(outcome: SavedSnippetFormatting): ReformattingFailure =
        when (outcome) {
            is SavedSnippetFormatting.Unformattable -> definitive("UNFORMATTABLE")
            is SavedSnippetFormatting.InvalidRules -> definitive("INVALID_RULES")
            is SavedSnippetFormatting.Unsupported -> definitive("UNSUPPORTED_VERSION")
            SavedSnippetFormatting.CodeTooLarge -> definitive("CODE_TOO_LARGE")
            SavedSnippetFormatting.OutputTooLarge -> definitive("OUTPUT_TOO_LARGE")
            SavedSnippetFormatting.ContentBroken -> definitive("CONTENT_BROKEN")
            SavedSnippetFormatting.LanguageUnavailable -> transient("LANGUAGE_UNAVAILABLE")
            SavedSnippetFormatting.StorageUnavailable -> transient("STORAGE_UNAVAILABLE")
            SavedSnippetFormatting.Stale -> transient("CODE_CHANGED")
            is SavedSnippetFormatting.Refused, is SavedSnippetFormatting.Formatted -> transient("METADATA_UNAVAILABLE")
        }

    private fun definitive(reason: String) = ReformattingFailure(reason, definitive = true)

    private fun transient(reason: String) = ReformattingFailure(reason, definitive = false)
}
