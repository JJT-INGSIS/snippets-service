package com.jjt.ingsis.snippets.processing

import com.jjt.ingsis.snippets.background.JobStep
import com.jjt.ingsis.snippets.background.JobWork
import com.jjt.ingsis.snippets.background.RemotePermit
import com.jjt.ingsis.snippets.background.WorkItem
import com.jjt.ingsis.snippets.language.operations.ProcessingCommand
import com.jjt.ingsis.snippets.language.operations.ProcessingKind
import com.jjt.ingsis.snippets.language.operations.ProcessingOperation

sealed interface OperationPreparation {
    data class Ready(
        val command: ProcessingCommand,
        val context: Map<String, String>,
    ) : OperationPreparation

    data class Retry(
        val reason: String,
    ) : OperationPreparation

    data class Failed(
        val reason: String,
    ) : OperationPreparation

    data object Superseded : OperationPreparation
}

interface ProcessingReaction {
    val kind: ProcessingKind

    fun prepare(
        work: JobWork,
        item: WorkItem,
    ): OperationPreparation

    fun publish(
        work: JobWork,
        permit: RemotePermit,
        operation: ProcessingOperation,
    ): JobStep
}
