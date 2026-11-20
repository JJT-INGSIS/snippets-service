package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.lintingrules.LintingRulesView
import java.util.UUID

data class LintingRulesResponse(
    val language: String,
    val version: String,
    val revision: String,
    val changed: Boolean,
    val jobId: UUID?,
    val rules: List<ConfiguredLintResponse>,
) {
    companion object {
        fun from(view: LintingRulesView) =
            LintingRulesResponse(
                language = view.language,
                version = view.version,
                revision = view.revision,
                changed = view.changed,
                jobId = view.jobId,
                rules =
                    view.rules.map { configured ->
                        ConfiguredLintResponse(
                            id = configured.rule.id,
                            type = configured.rule.type,
                            acceptsNull = configured.rule.acceptsNull,
                            choices = configured.rule.choices,
                            conflictsWith = configured.rule.conflictsWith,
                            value = configured.value,
                        )
                    },
            )
    }
}

data class ConfiguredLintResponse(
    val id: String,
    val type: String,
    val acceptsNull: Boolean,
    val choices: List<String>,
    val conflictsWith: List<String>,
    val value: Any?,
)
