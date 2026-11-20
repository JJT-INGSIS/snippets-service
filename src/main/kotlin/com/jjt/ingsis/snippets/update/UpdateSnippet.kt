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
        when (val prepared = preparation.prepare(draft, snippetId, context)) {
            is UpdatePreparationResult.Prepared -> {
                val outcome = replacement.apply(prepared.candidate)
                if (outcome is UpdateOutcome.Updated) {
                    lint.assess(outcome.snippet.id, prepared.candidate.actorId, draft)
                }
                outcome
            }

            is UpdatePreparationResult.Rejected -> {
                UpdateOutcome.Rejected(prepared.reason)
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
}
