package com.jjt.ingsis.snippets.language.printscript

import com.jjt.ingsis.snippets.language.formatting.FormattingCatalogResult
import com.jjt.ingsis.snippets.language.formatting.FormattingCheckResult

internal object PrintScriptRuleAnswers {
    fun catalogOf(
        answer: PrintScriptAnswer<PrintScriptFormattingCatalog>,
        language: String,
        version: String,
    ): FormattingCatalogResult =
        when (answer) {
            is PrintScriptAnswer.Answered -> {
                answer.body?.let { FormattingCatalogResult.Catalog(it.rules) }
                    ?: FormattingCatalogResult.Failed(UNAVAILABLE)
            }

            is PrintScriptAnswer.Rejected -> {
                if (answer.isUnsupportedVersion) {
                    FormattingCatalogResult.UnsupportedVersion(language, version)
                } else {
                    FormattingCatalogResult.Failed(UNAVAILABLE)
                }
            }

            PrintScriptAnswer.Unavailable -> {
                FormattingCatalogResult.Failed(UNAVAILABLE)
            }
        }

    fun checkOf(
        answer: PrintScriptAnswer<PrintScriptRulesCheck>,
        language: String,
        version: String,
        rules: Map<String, Any?>,
    ): FormattingCheckResult =
        when (answer) {
            is PrintScriptAnswer.Answered -> {
                checkedFrom(answer.body, rules)
            }

            is PrintScriptAnswer.Rejected -> {
                if (answer.isUnsupportedVersion) {
                    FormattingCheckResult.UnsupportedVersion(language, version)
                } else {
                    FormattingCheckResult.Failed(UNAVAILABLE)
                }
            }

            PrintScriptAnswer.Unavailable -> {
                FormattingCheckResult.Failed(UNAVAILABLE)
            }
        }

    private fun checkedFrom(
        body: PrintScriptRulesCheck?,
        rules: Map<String, Any?>,
    ): FormattingCheckResult =
        when {
            body == null -> FormattingCheckResult.Failed(UNAVAILABLE)
            body.valid -> FormattingCheckResult.Accepted(selectionOf(rules))
            else -> FormattingCheckResult.InvalidRules(body.violations)
        }

    private fun selectionOf(rules: Map<String, Any?>): Map<String, Any> =
        rules.mapNotNull { (id, value) -> value?.takeIf { it != false }?.let { id to it } }.toMap()
}
