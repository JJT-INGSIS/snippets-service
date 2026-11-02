package com.jjt.ingsis.snippets.lintingrules

import com.jjt.ingsis.snippets.background.NewBackgroundJob
import java.util.UUID

data class LintingScope(
    val ownerId: String,
    val language: String,
    val version: String,
)

data class SavedLintingRules(
    val configurationId: UUID,
    val selection: LintingSelection,
)

sealed interface RulesReplacement {
    data class Changed(
        val configurationId: UUID,
        val jobId: UUID,
    ) : RulesReplacement

    data object Unchanged : RulesReplacement
}

interface LintingRulesStore {
    fun find(scope: LintingScope): SavedLintingRules?

    fun replace(
        scope: LintingScope,
        selection: LintingSelection,
        jobFor: (configurationId: UUID) -> NewBackgroundJob,
    ): RulesReplacement
}
