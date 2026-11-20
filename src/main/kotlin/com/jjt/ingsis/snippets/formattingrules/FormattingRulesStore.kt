package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.background.NewBackgroundJob
import java.util.UUID

data class FormattingScope(
    val ownerId: String,
    val language: String,
    val version: String,
)

data class SavedFormattingRules(
    val configurationId: UUID,
    val selection: FormattingSelection,
)

sealed interface RulesReplacement {
    data class Changed(
        val configurationId: UUID,
        val jobId: UUID,
    ) : RulesReplacement

    data object Unchanged : RulesReplacement
}

interface FormattingRulesStore {
    fun find(scope: FormattingScope): SavedFormattingRules?

    fun replace(
        scope: FormattingScope,
        selection: FormattingSelection,
        jobFor: (configurationId: UUID) -> NewBackgroundJob,
    ): RulesReplacement
}
