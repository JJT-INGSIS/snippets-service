package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.access.ReadDecision
import com.jjt.ingsis.snippets.access.ReadFailure
import com.jjt.ingsis.snippets.access.ReadGate
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import org.springframework.stereotype.Service

private const val MAX_ATTEMPTS = 2

@Service
class FormatSavedSnippet(
    private val gate: ReadGate,
    private val formatter: OwnerRulesFormatter,
    private val links: SnippetContentLinks,
    private val rules: ApplicableFormattingRules,
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
            val formatted = formatter.format(snippet, ownerId)
            if (formatted is SavedSnippetFormatting.Formatted) confirmed(formatted, ownerId, attempts) else formatted
        } catch (_: MetadataPersistenceException) {
            SavedSnippetFormatting.Refused(ReadFailure.METADATA_UNAVAILABLE)
        }

    private fun confirmed(
        formatted: SavedSnippetFormatting.Formatted,
        ownerId: String,
        attempts: Int,
    ): SavedSnippetFormatting {
        val current = links.findLinked(formatted.snippet.id) ?: return SavedSnippetFormatting.Stale
        val now = FormattingBasis.of(current, rules.of(current.metadata, ownerId), formatted.basis.processor)

        return when {
            now != null && formatted.basis.isCurrent(now) -> formatted.copy(snippet = current.metadata)
            attempts > 1 -> attempt(current, ownerId, attempts - 1)
            else -> SavedSnippetFormatting.Stale
        }
    }
}
