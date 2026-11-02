package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.language.Attributed
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.ProcessorCheck
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.language.formatting.FormattingCatalogResult
import com.jjt.ingsis.snippets.language.formatting.FormattingCheckResult
import com.jjt.ingsis.snippets.language.formatting.FormattingRule
import org.springframework.stereotype.Component

sealed interface CatalogLookup {
    data class Found(
        val rules: List<FormattingRule>,
    ) : CatalogLookup

    data class Refused(
        val outcome: FormattingRulesOutcome,
    ) : CatalogLookup
}

sealed interface RulesAcceptance {
    data class Accepted(
        val selection: FormattingSelection,
        val catalog: List<FormattingRule>,
        val processor: ProcessorRevision,
    ) : RulesAcceptance

    data class Refused(
        val outcome: FormattingRulesOutcome,
    ) : RulesAcceptance
}

@Component
class FormattingRulesProvider(
    private val language: Language,
) {
    fun catalog(scope: FormattingScope): CatalogLookup =
        when (val result = language.formattingCatalog(scope.language, scope.version).result) {
            is FormattingCatalogResult.Catalog -> CatalogLookup.Found(result.rules)
            else -> CatalogLookup.Refused(FormattingRefusals.of(result))
        }

    fun accept(
        scope: FormattingScope,
        rules: Map<String, Any?>,
    ): RulesAcceptance {
        val checked = language.checkFormattingRules(scope.language, scope.version, rules)
        val accepted =
            checked.result as? FormattingCheckResult.Accepted
                ?: return RulesAcceptance.Refused(FormattingRefusals.of(checked.result))
        val catalog = language.formattingCatalog(scope.language, scope.version)
        val rulesOfVersion =
            catalog.result as? FormattingCatalogResult.Catalog
                ?: return RulesAcceptance.Refused(FormattingRefusals.of(catalog.result))
        val processor =
            sameVerifiedProcessor(checked, catalog)
                ?: return RulesAcceptance.Refused(FormattingRulesOutcome.LanguageUnavailable)

        return RulesAcceptance.Accepted(FormattingSelection(accepted.selection), rulesOfVersion.rules, processor)
    }

    private fun sameVerifiedProcessor(
        checked: Attributed<FormattingCheckResult>,
        catalog: Attributed<FormattingCatalogResult>,
    ): ProcessorRevision? =
        checked.processor
            .verifiedOrNull()
            ?.takeIf { catalog.processor.checkAgainst(it) == ProcessorCheck.ACCREDITED }
}
