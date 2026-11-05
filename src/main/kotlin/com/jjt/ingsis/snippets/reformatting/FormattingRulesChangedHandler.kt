package com.jjt.ingsis.snippets.reformatting

import com.jjt.ingsis.snippets.background.BackgroundHandler
import com.jjt.ingsis.snippets.background.EventType
import com.jjt.ingsis.snippets.background.JobStep
import com.jjt.ingsis.snippets.background.JobWork
import com.jjt.ingsis.snippets.background.RemotePermit
import com.jjt.ingsis.snippets.formattingrules.FormattingRulesStore
import com.jjt.ingsis.snippets.formattingrules.FormattingSelection
import org.springframework.stereotype.Component

@Component
class FormattingRulesChangedHandler(
    private val rules: FormattingRulesStore,
    private val selection: OwnedSnippetSelection,
    private val reformatting: SnippetReformatting,
) : BackgroundHandler {
    override val eventType: EventType = EventType.FormattingRulesChanged

    override fun step(work: JobWork): JobStep {
        val target = ReformattingTarget.of(work.job.event) ?: return JobStep.Failed("INVALID_EVENT_SCOPE")
        if (!rulesStillCurrent(target)) return JobStep.Superseded
        if (!work.job.selectionComplete && !selection.selectPage(work, target)) {
            return JobStep.Retry("PERMISSIONS_UNAVAILABLE")
        }
        val item = work.items().firstOrNull() ?: return JobStep.Complete

        return reformatting.process(work, target, item)
    }

    override fun reconcile(
        work: JobWork,
        permit: RemotePermit?,
    ): JobStep = reformatting.recover(work, permit)

    private fun rulesStillCurrent(target: ReformattingTarget): Boolean =
        (rules.find(target.scope)?.selection ?: FormattingSelection.EMPTY).revision == target.rulesRevision
}
