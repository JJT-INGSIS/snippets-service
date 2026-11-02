package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.background.NewBackgroundJob
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class SaveFormattingRules(
    private val identity: ActorIdentityResolver,
    private val provider: FormattingRulesProvider,
    private val store: FormattingRulesStore,
) {
    fun save(
        language: String,
        version: String,
        rules: Map<String, Any?>,
        context: ActorContext,
    ): FormattingRulesOutcome {
        val owner =
            identity.resolve(context) as? ActorIdentity.Identified ?: return FormattingRulesOutcome.IdentityRejected
        val scope = FormattingScope(owner.actorId, language, version)

        return when (val acceptance = provider.accept(scope, rules)) {
            is RulesAcceptance.Accepted -> replace(scope, acceptance)
            is RulesAcceptance.Refused -> acceptance.outcome
        }
    }

    private fun replace(
        scope: FormattingScope,
        accepted: RulesAcceptance.Accepted,
    ): FormattingRulesOutcome =
        try {
            val replacement = store.replace(scope, accepted.selection) { id -> changedJob(scope, accepted, id) }
            val view =
                FormattingRulesView.of(
                    scope = scope,
                    catalog = accepted.catalog,
                    selection = accepted.selection,
                    replacement = replacement,
                )
            FormattingRulesOutcome.Shown(view)
        } catch (_: MetadataPersistenceException) {
            FormattingRulesOutcome.MetadataUnavailable
        }

    private fun changedJob(
        scope: FormattingScope,
        accepted: RulesAcceptance.Accepted,
        configurationId: UUID,
    ): NewBackgroundJob =
        FormattingRulesChangedJob.of(
            scope = scope,
            configurationId = configurationId,
            selection = accepted.selection,
            processor = accepted.processor,
        )
}
