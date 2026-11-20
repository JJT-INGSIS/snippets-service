package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.access.ReadDecision
import com.jjt.ingsis.snippets.access.ReadFailure
import com.jjt.ingsis.snippets.access.ReadGate
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import org.springframework.stereotype.Service

@Service
class DownloadFormattedSnippet(
    private val gate: ReadGate,
    private val stored: CurrentStoredVariant,
    private val onDemand: FormatSavedSnippet,
    private val language: Language,
) {
    fun download(
        snippetId: String?,
        context: ActorContext,
    ): FormattedDownload =
        when (val decision = gate.open(snippetId, context)) {
            is ReadDecision.Granted -> prepare(decision.snippet, decision.access.ownerId)
            is ReadDecision.Refused -> FormattedDownload.NotReady(SavedSnippetFormatting.Refused(decision.failure))
        }

    private fun prepare(
        snippet: LinkedSnippet,
        ownerId: String,
    ): FormattedDownload =
        try {
            val variant = stored.read(snippet, ownerId)
            if (variant != null) {
                ready(
                    snippet = variant.snippet,
                    code = variant.code,
                    basis = variant.basis,
                    source = VariantSource.STORED,
                )
            } else {
                formatNow(snippet, ownerId)
            }
        } catch (_: MetadataPersistenceException) {
            FormattedDownload.NotReady(SavedSnippetFormatting.Refused(ReadFailure.METADATA_UNAVAILABLE))
        }

    private fun formatNow(
        snippet: LinkedSnippet,
        ownerId: String,
    ): FormattedDownload =
        when (val formatted = onDemand.format(snippet, ownerId)) {
            is SavedSnippetFormatting.Formatted -> {
                ready(
                    snippet = formatted.snippet,
                    code = formatted.code,
                    basis = formatted.basis,
                    source = VariantSource.ON_DEMAND,
                )
            }

            else -> {
                FormattedDownload.NotReady(formatted)
            }
        }

    private fun ready(
        snippet: SnippetMetadata,
        code: String,
        basis: FormattingBasis,
        source: VariantSource,
    ): FormattedDownload {
        val extension = language.extensionOf(snippet.language) ?: return unsupported(snippet)

        return FormattedDownload.Ready(
            snippet = snippet,
            extension = extension,
            code = code,
            basis = basis,
            source = source,
        )
    }

    private fun unsupported(snippet: SnippetMetadata): FormattedDownload =
        FormattedDownload.NotReady(SavedSnippetFormatting.Unsupported(snippet.language, snippet.version))
}
