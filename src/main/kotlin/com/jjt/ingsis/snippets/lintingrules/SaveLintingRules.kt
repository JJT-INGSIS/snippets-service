package com.jjt.ingsis.snippets.lintingrules

import com.jjt.ingsis.snippets.background.NewBackgroundJob
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class SaveLintingRules(
    private val identity: ActorIdentityResolver,
    private val provider: LintingRulesProvider,
    private val store: LintingRulesStore,
) {
    fun save(
        language: String,
        version: String,
        rules: Map<String, Any?>,
        context: ActorContext,
    ): LintingRulesOutcome {
        val owner =
            identity.resolve(context) as? ActorIdentity.Identified ?: return LintingRulesOutcome.IdentityRejected
        val scope = LintingScope(owner.actorId, language, version)

        return when (val acceptance = provider.accept(scope, rules)) {
            is RulesAcceptance.Accepted -> replace(scope, acceptance)
            is RulesAcceptance.Refused -> acceptance.outcome
        }
    }

    private fun replace(
        scope: LintingScope,
        accepted: RulesAcceptance.Accepted,
    ): LintingRulesOutcome =
        try {
            val replacement =
                store.replace(
                    scope,
                    accepted.selection,
                    accepted.implicitSelection,
                ) { id -> changedJob(scope, accepted, id) }
            val view =
                LintingRulesView.of(
                    scope = scope,
                    catalog = accepted.catalog,
                    selection = accepted.selection,
                    replacement = replacement,
                )
            LintingRulesOutcome.Shown(view)
        } catch (_: MetadataPersistenceException) {
            LintingRulesOutcome.MetadataUnavailable
        }

    private fun changedJob(
        scope: LintingScope,
        accepted: RulesAcceptance.Accepted,
        configurationId: UUID,
    ): NewBackgroundJob =
        LintingRulesChangedJob.of(
            scope = scope,
            configurationId = configurationId,
            selection = accepted.selection,
            processor = accepted.processor,
        )
}
