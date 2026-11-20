package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.language.formatting.FormattingRule
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import org.springframework.stereotype.Service

@Service
class ReadFormattingRules(
    private val identity: ActorIdentityResolver,
    private val provider: FormattingRulesProvider,
    private val store: FormattingRulesStore,
) {
    fun read(
        language: String,
        version: String,
        context: ActorContext,
    ): FormattingRulesOutcome {
        val owner =
            identity.resolve(context) as? ActorIdentity.Identified ?: return FormattingRulesOutcome.IdentityRejected
        val scope = FormattingScope(owner.actorId, language, version)

        return when (val catalog = provider.catalog(scope)) {
            is CatalogLookup.Found -> shown(scope, catalog.rules)
            is CatalogLookup.Refused -> catalog.outcome
        }
    }

    private fun shown(
        scope: FormattingScope,
        catalog: List<FormattingRule>,
    ): FormattingRulesOutcome =
        try {
            val selection = store.find(scope)?.selection ?: FormattingSelection.EMPTY
            FormattingRulesOutcome.Shown(FormattingRulesView.of(scope, catalog, selection))
        } catch (_: MetadataPersistenceException) {
            FormattingRulesOutcome.MetadataUnavailable
        }
}
