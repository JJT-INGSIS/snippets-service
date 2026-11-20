package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.access.ReadFailure
import com.jjt.ingsis.snippets.formattingrules.FormattedDownload
import com.jjt.ingsis.snippets.formattingrules.SavedSnippetFormatting
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

@Component
class FormattedDownloadTranslator {
    fun toResponse(download: FormattedDownload): ResponseEntity<*> =
        when (download) {
            is FormattedDownload.Ready -> ResponseEntity.ok(FormattedSnippetResponse.from(download))
            is FormattedDownload.NotReady -> problemFor(download.reason)
        }

    private fun problemFor(reason: SavedSnippetFormatting): ResponseEntity<*> =
        when (reason) {
            is SavedSnippetFormatting.Refused -> refused(reason.failure)
            is SavedSnippetFormatting.Unformattable -> unformattable(reason)
            is SavedSnippetFormatting.InvalidRules -> invalidRules(reason)
            is SavedSnippetFormatting.Unsupported -> SnippetProblem.UNSUPPORTED_VERSION.response()
            SavedSnippetFormatting.CodeTooLarge -> limitExceeded("code")
            SavedSnippetFormatting.OutputTooLarge -> limitExceeded("output")
            SavedSnippetFormatting.LanguageUnavailable -> SnippetProblem.DEPENDENCY_FAILURE.response()
            SavedSnippetFormatting.StorageUnavailable -> SnippetProblem.STORAGE_UNAVAILABLE.response()
            SavedSnippetFormatting.Stale -> SnippetProblem.FORMATTING_STALE.response()
            SavedSnippetFormatting.ContentBroken -> SnippetProblem.TECHNICAL_FAILURE.response()
            is SavedSnippetFormatting.Formatted -> SnippetProblem.TECHNICAL_FAILURE.response()
        }

    private fun refused(failure: ReadFailure): ResponseEntity<*> =
        when (failure) {
            ReadFailure.INVALID_ID -> SnippetProblem.INVALID_REQUEST.response("field", "snippetId")
            ReadFailure.IDENTITY_REJECTED -> SnippetProblem.IDENTITY_UNAVAILABLE.response()
            ReadFailure.SNIPPET_MISSING -> SnippetProblem.SNIPPET_NOT_FOUND.response()
            ReadFailure.PERMISSIONS_UNAVAILABLE -> SnippetProblem.DEPENDENCY_FAILURE.response()
            ReadFailure.METADATA_UNAVAILABLE -> SnippetProblem.METADATA_UNAVAILABLE.response()
        }

    private fun unformattable(reason: SavedSnippetFormatting.Unformattable): ResponseEntity<*> =
        SnippetProblem.UNFORMATTABLE_CODE.response("diagnostics", reason.diagnostics)

    private fun invalidRules(reason: SavedSnippetFormatting.InvalidRules): ResponseEntity<*> =
        SnippetProblem.INVALID_FORMATTING_RULES.response("violations", reason.violations)

    private fun limitExceeded(limit: String): ResponseEntity<*> =
        SnippetProblem.FORMATTING_LIMIT_EXCEEDED.response("limit", limit)
}
