package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.access.ReadDecision
import com.jjt.ingsis.snippets.access.ReadFailure
import com.jjt.ingsis.snippets.access.ReadGate
import com.jjt.ingsis.snippets.detail.ContentReading
import com.jjt.ingsis.snippets.detail.StableContentRead
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.formatting.FormattingResult
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import org.springframework.stereotype.Service

private const val MAX_ATTEMPTS = 2

@Service
class FormatSavedSnippet(
    private val gate: ReadGate,
    private val content: ContentReading,
    private val links: SnippetContentLinks,
    private val rules: ApplicableFormattingRules,
    private val language: Language,
) {
    fun format(
        snippetId: String?,
        context: ActorContext,
    ): SavedSnippetFormatting =
        when (val decision = gate.open(snippetId, context)) {
            is ReadDecision.Granted -> attempt(decision.snippet, decision.access.ownerId, MAX_ATTEMPTS)
            is ReadDecision.Refused -> SavedSnippetFormatting.Refused(decision.failure)
        }

    private fun attempt(
        snippet: LinkedSnippet,
        ownerId: String,
        attempts: Int,
    ): SavedSnippetFormatting =
        try {
            when (val read = content.readStable(snippet)) {
                is StableContentRead.Read -> formatRead(read, ownerId, attempts)
                StableContentRead.Conflict -> SavedSnippetFormatting.Stale
                StableContentRead.Unavailable -> SavedSnippetFormatting.StorageUnavailable
                StableContentRead.Broken -> SavedSnippetFormatting.ContentBroken
            }
        } catch (_: MetadataPersistenceException) {
            SavedSnippetFormatting.Refused(ReadFailure.METADATA_UNAVAILABLE)
        }

    private fun formatRead(
        read: StableContentRead.Read,
        ownerId: String,
        attempts: Int,
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
        val used = FormattingBasis.of(read.snippet, selection, processor) ?: return SavedSnippetFormatting.ContentBroken

        val current = links.findLinked(metadata.id) ?: return SavedSnippetFormatting.Stale
        val now = FormattingBasis.of(current, rules.of(current.metadata, ownerId), processor)

        return when {
            now != null && used.isCurrent(now) -> SavedSnippetFormatting.Formatted(current.metadata, result.code, used)
            attempts > 1 -> attempt(current, ownerId, attempts - 1)
            else -> SavedSnippetFormatting.Stale
        }
    }
}
