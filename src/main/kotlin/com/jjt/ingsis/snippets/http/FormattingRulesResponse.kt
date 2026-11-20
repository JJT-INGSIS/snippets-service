package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.formattingrules.ConfiguredRule
import com.jjt.ingsis.snippets.formattingrules.FormattingRulesView
import java.util.UUID

data class FormattingRulesResponse(
    val language: String,
    val version: String,
    val revision: String,
    val changed: Boolean,
    val jobId: UUID?,
    val rules: List<ConfiguredRuleResponse>,
) {
    companion object {
        fun from(view: FormattingRulesView): FormattingRulesResponse =
            FormattingRulesResponse(
                language = view.language,
                version = view.version,
                revision = view.revision,
                changed = view.changed,
                jobId = view.jobId,
                rules = view.rules.map(ConfiguredRuleResponse::from),
            )
    }
}

data class ConfiguredRuleResponse(
    val id: String,
    val type: String,
    val default: Boolean?,
    val minimum: Int?,
    val maximum: Int?,
    val conflictsWith: List<String>,
    val description: String,
    val value: Any?,
) {
    companion object {
        fun from(configured: ConfiguredRule): ConfiguredRuleResponse =
            ConfiguredRuleResponse(
                id = configured.rule.id,
                type = configured.rule.type,
                default = configured.rule.default,
                minimum = configured.rule.minimum,
                maximum = configured.rule.maximum,
                conflictsWith = configured.rule.conflictsWith,
                description = configured.rule.description,
                value = configured.value,
            )
    }
}
