package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.access.ReadFailure
import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.language.formatting.FormattingResult
import com.jjt.ingsis.snippets.language.formatting.FormattingRuleViolation
import com.jjt.ingsis.snippets.metadata.SnippetMetadata

sealed interface SavedSnippetFormatting {
    data class Formatted(
        val snippet: SnippetMetadata,
        val code: String,
        val basis: FormattingBasis,
    ) : SavedSnippetFormatting

    data class Refused(
        val failure: ReadFailure,
    ) : SavedSnippetFormatting

    data class Unformattable(
        val diagnostics: List<Diagnostic>,
    ) : SavedSnippetFormatting

    data class InvalidRules(
        val violations: List<FormattingRuleViolation>,
    ) : SavedSnippetFormatting

    data class Unsupported(
        val language: String,
        val version: String,
    ) : SavedSnippetFormatting

    data object CodeTooLarge : SavedSnippetFormatting

    data object OutputTooLarge : SavedSnippetFormatting

    data object LanguageUnavailable : SavedSnippetFormatting

    data object StorageUnavailable : SavedSnippetFormatting

    data object ContentBroken : SavedSnippetFormatting

    data object Stale : SavedSnippetFormatting

    companion object {
        fun notFormatted(
            result: FormattingResult,
            snippet: SnippetMetadata,
        ): SavedSnippetFormatting =
            when (result) {
                is FormattingResult.Unformattable -> {
                    Unformattable(result.diagnostics)
                }

                is FormattingResult.InvalidRules -> {
                    InvalidRules(result.violations)
                }

                FormattingResult.CodeTooLarge -> {
                    CodeTooLarge
                }

                FormattingResult.OutputTooLarge -> {
                    OutputTooLarge
                }

                is FormattingResult.UnsupportedLanguage, is FormattingResult.UnsupportedVersion -> {
                    Unsupported(snippet.language, snippet.version)
                }

                is FormattingResult.Formatted, is FormattingResult.Failed -> {
                    LanguageUnavailable
                }
            }
    }
}
