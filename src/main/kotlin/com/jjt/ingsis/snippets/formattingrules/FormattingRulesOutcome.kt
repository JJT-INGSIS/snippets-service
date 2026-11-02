package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.language.formatting.FormattingRule
import com.jjt.ingsis.snippets.language.formatting.FormattingRuleViolation
import java.util.UUID

data class ConfiguredRule(
    val rule: FormattingRule,
    val value: Any?,
)

data class FormattingRulesView(
    val language: String,
    val version: String,
    val revision: String,
    val changed: Boolean,
    val jobId: UUID?,
    val rules: List<ConfiguredRule>,
) {
    companion object {
        fun of(
            scope: FormattingScope,
            catalog: List<FormattingRule>,
            selection: FormattingSelection,
            replacement: RulesReplacement = RulesReplacement.Unchanged,
        ): FormattingRulesView =
            FormattingRulesView(
                language = scope.language,
                version = scope.version,
                revision = selection.revision,
                changed = replacement is RulesReplacement.Changed,
                jobId = (replacement as? RulesReplacement.Changed)?.jobId,
                rules = catalog.map { rule -> ConfiguredRule(rule, selection.rules[rule.id] ?: rule.default) },
            )
    }
}

sealed interface FormattingRulesOutcome {
    data class Shown(
        val view: FormattingRulesView,
    ) : FormattingRulesOutcome

    data class InvalidRules(
        val violations: List<FormattingRuleViolation>,
    ) : FormattingRulesOutcome

    data class UnsupportedLanguage(
        val language: String,
    ) : FormattingRulesOutcome

    data class UnsupportedVersion(
        val language: String,
        val version: String,
    ) : FormattingRulesOutcome

    data object LanguageUnavailable : FormattingRulesOutcome

    data object IdentityRejected : FormattingRulesOutcome

    data object MetadataUnavailable : FormattingRulesOutcome
}
