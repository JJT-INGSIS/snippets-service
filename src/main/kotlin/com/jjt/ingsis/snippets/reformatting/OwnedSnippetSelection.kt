package com.jjt.ingsis.snippets.reformatting

import com.jjt.ingsis.snippets.background.JobWork
import com.jjt.ingsis.snippets.processing.RuleBatchScope
import com.jjt.ingsis.snippets.processing.RuleBatchSelection
import org.springframework.stereotype.Component

@Component
class OwnedSnippetSelection(
    private val selection: RuleBatchSelection,
) {
    fun selectPage(
        work: JobWork,
        target: ReformattingTarget,
    ): Boolean =
        selection.select(
            work = work,
            scope = RuleBatchScope(target.scope.ownerId, target.scope.language, target.scope.version),
            revisionKey = "formattingRules",
            revision = target.rulesRevision,
        )
}
