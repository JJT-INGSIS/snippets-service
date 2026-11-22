package com.jjt.ingsis.snippets.update

import com.jjt.ingsis.snippets.creation.SnippetDraft
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.lintingrules.SavedSnippetLint
import org.springframework.stereotype.Service

@Service
class UpdateSnippet(
    private val preparation: PrepareUpdate,
    private val replacement: SnippetReplacement,
    private val lint: SavedSnippetLint,
) {
    fun update(
        draft: SnippetDraft,
        snippetId: String?,
        context: ActorContext,
    ): UpdateOutcome =
        when (val preparationResult = preparation.prepare(draft, snippetId, context)) {
            is UpdatePreparationResult.Prepared -> {
                replaceCodeAndAssessLint(preparationResult.candidate, draft)
            }

            is UpdatePreparationResult.Rejected -> {
                UpdateOutcome.Rejected(preparationResult.reason)
            }

            UpdatePreparationResult.Missing -> {
                UpdateOutcome.Missing
            }

            UpdatePreparationResult.Pending -> {
                UpdateOutcome.Pending
            }

            UpdatePreparationResult.Denied -> {
                UpdateOutcome.Denied
            }

            UpdatePreparationResult.OwnershipMissing -> {
                UpdateOutcome.OwnershipMissing
            }
        }

    private fun replaceCodeAndAssessLint(
        candidate: UpdateCandidate,
        draft: SnippetDraft,
    ): UpdateOutcome {
        val replacementOutcome = replacement.apply(candidate)
        if (replacementOutcome is UpdateOutcome.Updated) {
            lint.assess(replacementOutcome.snippet.id, candidate.actorId, draft)
        }
        return replacementOutcome
    }
}
