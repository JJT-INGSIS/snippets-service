package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.language.formatting.FormattingCatalogResult
import com.jjt.ingsis.snippets.language.formatting.FormattingCheckResult

internal object FormattingRefusals {
    fun of(result: FormattingCheckResult): FormattingRulesOutcome =
        when (result) {
            is FormattingCheckResult.InvalidRules -> {
                FormattingRulesOutcome.InvalidRules(result.violations)
            }

            is FormattingCheckResult.UnsupportedLanguage -> {
                FormattingRulesOutcome.UnsupportedLanguage(result.language)
            }

            is FormattingCheckResult.UnsupportedVersion -> {
                FormattingRulesOutcome.UnsupportedVersion(result.language, result.version)
            }

            is FormattingCheckResult.Accepted, is FormattingCheckResult.Failed -> {
                FormattingRulesOutcome.LanguageUnavailable
            }
        }

    fun of(result: FormattingCatalogResult): FormattingRulesOutcome =
        when (result) {
            is FormattingCatalogResult.UnsupportedLanguage -> {
                FormattingRulesOutcome.UnsupportedLanguage(result.language)
            }

            is FormattingCatalogResult.UnsupportedVersion -> {
                FormattingRulesOutcome.UnsupportedVersion(result.language, result.version)
            }

            is FormattingCatalogResult.Catalog, is FormattingCatalogResult.Failed -> {
                FormattingRulesOutcome.LanguageUnavailable
            }
        }
}
