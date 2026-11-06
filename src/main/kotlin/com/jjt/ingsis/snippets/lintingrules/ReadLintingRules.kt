package com.jjt.ingsis.snippets.lintingrules

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import org.springframework.stereotype.Service

@Service
class ReadLintingRules(
    private val identity: ActorIdentityResolver,
    private val provider: LintingRulesProvider,
    private val store: LintingRulesStore,
) {
    fun read(
        language: String,
        version: String,
        context: ActorContext,
    ): LintingRulesOutcome {
        val owner =
            identity.resolve(context) as? ActorIdentity.Identified ?: return LintingRulesOutcome.IdentityRejected
        val scope = LintingScope(owner.actorId, language, version)

        return when (val catalog = provider.catalog(scope)) {
            is CatalogLookup.Found -> shown(scope, catalog)
            is CatalogLookup.Refused -> catalog.outcome
        }
    }

    private fun shown(
        scope: LintingScope,
        catalog: CatalogLookup.Found,
    ): LintingRulesOutcome =
        try {
            val selection = store.find(scope)?.selection ?: catalog.verifiedDefaults
            if (selection == null) {
                LintingRulesOutcome.LanguageUnavailable
            } else {
                LintingRulesOutcome.Shown(LintingRulesView.of(scope, catalog.rules, selection))
            }
        } catch (_: MetadataPersistenceException) {
            LintingRulesOutcome.MetadataUnavailable
        }
}
