package com.jjt.ingsis.snippets.lintingrules

import com.jjt.ingsis.snippets.language.linting.LintRule
import com.jjt.ingsis.snippets.language.linting.LintViolation
import java.util.UUID

data class ConfiguredLintRule(
    val rule: LintRule,
    val value: Any?,
)

data class LintingRulesView(
    val language: String,
    val version: String,
    val revision: String,
    val changed: Boolean,
    val jobId: UUID?,
    val rules: List<ConfiguredLintRule>,
) {
    companion object {
        fun of(
            scope: LintingScope,
            catalog: List<LintRule>,
            selection: LintingSelection,
            replacement: RulesReplacement = RulesReplacement.Unchanged,
        ) = LintingRulesView(
            language = scope.language,
            version = scope.version,
            revision = selection.revision,
            changed = replacement is RulesReplacement.Changed,
            jobId = (replacement as? RulesReplacement.Changed)?.jobId,
            rules = catalog.map { ConfiguredLintRule(it, selection.rules[it.id]) },
        )
    }
}

sealed interface LintingRulesOutcome {
    data class Shown(
        val view: LintingRulesView,
    ) : LintingRulesOutcome

    data class InvalidRules(
        val violations: List<LintViolation>,
    ) : LintingRulesOutcome

    data class UnsupportedLanguage(
        val language: String,
    ) : LintingRulesOutcome

    data class UnsupportedVersion(
        val language: String,
        val version: String,
    ) : LintingRulesOutcome

    data object LanguageUnavailable : LintingRulesOutcome

    data object IdentityRejected : LintingRulesOutcome

    data object MetadataUnavailable : LintingRulesOutcome
}
