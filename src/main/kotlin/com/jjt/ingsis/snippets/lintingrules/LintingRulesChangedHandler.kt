package com.jjt.ingsis.snippets.lintingrules

import com.jjt.ingsis.snippets.background.BackgroundHandler
import com.jjt.ingsis.snippets.background.DomainEvent
import com.jjt.ingsis.snippets.background.EventType
import com.jjt.ingsis.snippets.background.JobStep
import com.jjt.ingsis.snippets.background.JobWork
import com.jjt.ingsis.snippets.background.RemotePermit
import com.jjt.ingsis.snippets.language.operations.ProcessingKind
import com.jjt.ingsis.snippets.processing.DurableProcessing
import com.jjt.ingsis.snippets.processing.RuleBatchScope
import com.jjt.ingsis.snippets.processing.RuleBatchSelection
import org.springframework.stereotype.Component

internal fun DomainEvent.lintScope(): LintingScope? =
    LintingScope(
        scope["ownerId"] ?: return null,
        scope["language"] ?: return null,
        scope["version"] ?: return null,
    )

@Component
class LintingRulesChangedHandler(
    private val rules: LintingRulesStore,
    private val selection: RuleBatchSelection,
    private val processing: DurableProcessing,
) : BackgroundHandler {
    override val eventType = EventType.LintRulesChanged

    override fun step(work: JobWork): JobStep {
        val scope = work.job.event.lintScope() ?: return JobStep.Failed("INVALID_EVENT_SCOPE")
        val revision = work.job.event.revisions["lintRules"] ?: return JobStep.Failed("MISSING_RULES_REVISION")
        if (rules.find(scope)?.selection?.revision != revision) return JobStep.Superseded
        if (!work.job.selectionComplete &&
            !selection.select(
                work = work,
                scope = RuleBatchScope(scope.ownerId, scope.language, scope.version),
                revisionKey = "lintRules",
                revision = revision,
            )
        ) {
            return JobStep.Retry("PERMISSIONS_UNAVAILABLE")
        }
        val item = work.items().firstOrNull() ?: return JobStep.Complete
        return processing.start(work, item, ProcessingKind.LINT)
    }

    override fun reconcile(
        work: JobWork,
        permit: RemotePermit?,
    ): JobStep = processing.recover(work, permit, ProcessingKind.LINT)
}
