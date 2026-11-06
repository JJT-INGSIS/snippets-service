package com.jjt.ingsis.snippets.reformatting

import com.jjt.ingsis.snippets.background.JobStep
import com.jjt.ingsis.snippets.background.JobWork
import com.jjt.ingsis.snippets.background.RemotePermit
import com.jjt.ingsis.snippets.background.WorkItem
import com.jjt.ingsis.snippets.language.operations.ProcessingKind
import com.jjt.ingsis.snippets.processing.DurableProcessing
import org.springframework.stereotype.Component

@Component
class SnippetReformatting(
    private val processing: DurableProcessing,
) {
    fun process(
        work: JobWork,
        target: ReformattingTarget,
        item: WorkItem,
    ): JobStep {
        require(ReformattingTarget.of(work.job.event) == target)
        return processing.start(work, item, ProcessingKind.FORMAT)
    }

    fun recover(
        work: JobWork,
        permit: RemotePermit?,
    ): JobStep = processing.recover(work, permit, ProcessingKind.FORMAT)
}
