package com.jjt.ingsis.snippets.lintingrules

import com.jjt.ingsis.snippets.language.Attributed
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.ProcessorCheck
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.language.linting.LintCatalogResult
import com.jjt.ingsis.snippets.language.linting.LintCheckResult
import com.jjt.ingsis.snippets.language.linting.LintRule
import org.springframework.stereotype.Component

sealed interface CatalogLookup {
    data class Found(
        val rules: List<LintRule>,
        val defaults: LintingSelection?,
        val processor: ProcessorRevision?,
    ) : CatalogLookup {
        val verifiedDefaults: LintingSelection? get() = defaults.takeIf { processor != null }
    }

    data class Refused(
        val outcome: LintingRulesOutcome,
    ) : CatalogLookup
}

sealed interface RulesAcceptance {
    data class Accepted(
        val selection: LintingSelection,
        val catalog: List<LintRule>,
        val processor: ProcessorRevision,
        val implicitSelection: LintingSelection? = null,
    ) : RulesAcceptance

    data class Refused(
        val outcome: LintingRulesOutcome,
    ) : RulesAcceptance
}

@Component
class LintingRulesProvider(
    private val language: Language,
) {
    fun catalog(scope: LintingScope): CatalogLookup {
        val attributed = language.lintingCatalog(scope.language, scope.version)
        return when (val answer = attributed.result) {
            is LintCatalogResult.Catalog -> {
                CatalogLookup.Found(
                    answer.rules,
                    answer.defaults?.let(::LintingSelection),
                    attributed.processor.verifiedOrNull(),
                )
            }

            is LintCatalogResult.UnsupportedLanguage -> {
                CatalogLookup.Refused(
                    LintingRulesOutcome.UnsupportedLanguage(answer.language),
                )
            }

            is LintCatalogResult.UnsupportedVersion -> {
                CatalogLookup.Refused(
                    LintingRulesOutcome.UnsupportedVersion(answer.language, answer.version),
                )
            }

            is LintCatalogResult.Failed -> {
                CatalogLookup.Refused(LintingRulesOutcome.LanguageUnavailable)
            }
        }
    }

    fun defaults(scope: LintingScope): LintingSelection? = (catalog(scope) as? CatalogLookup.Found)?.verifiedDefaults

    fun accept(
        scope: LintingScope,
        rules: Map<String, Any?>,
    ): RulesAcceptance {
        val checked = language.checkLintingRules(scope.language, scope.version, rules)
        val refusal = refusal(checked.result)
        return if (refusal != null) RulesAcceptance.Refused(refusal) else accepted(scope, rules, checked)
    }

    private fun accepted(
        scope: LintingScope,
        rules: Map<String, Any?>,
        checked: Attributed<LintCheckResult>,
    ): RulesAcceptance {
        val catalog = language.lintingCatalog(scope.language, scope.version)
        val rulesOfVersion =
            catalog.result as? LintCatalogResult.Catalog
                ?: return RulesAcceptance.Refused(LintingRulesOutcome.LanguageUnavailable)
        val processor =
            checked.processor
                .verifiedOrNull()
                ?.takeIf { catalog.processor.checkAgainst(it) == ProcessorCheck.ACCREDITED }
                ?: return RulesAcceptance.Refused(LintingRulesOutcome.LanguageUnavailable)
        return RulesAcceptance.Accepted(
            selection = LintingSelection(rules.toMap()),
            catalog = rulesOfVersion.rules,
            processor = processor,
            implicitSelection = rulesOfVersion.defaults?.let(::LintingSelection),
        )
    }

    private fun refusal(result: LintCheckResult): LintingRulesOutcome? =
        when (result) {
            is LintCheckResult.UnsupportedLanguage -> {
                LintingRulesOutcome.UnsupportedLanguage(result.language)
            }

            is LintCheckResult.UnsupportedVersion -> {
                LintingRulesOutcome.UnsupportedVersion(
                    result.language,
                    result.version,
                )
            }

            is LintCheckResult.Failed -> {
                LintingRulesOutcome.LanguageUnavailable
            }

            is LintCheckResult.Checked -> {
                if (result.valid) {
                    null
                } else {
                    LintingRulesOutcome.InvalidRules(
                        result.violations,
                    )
                }
            }
        }
}
