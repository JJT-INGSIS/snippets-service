package com.jjt.ingsis.snippets.automatic

import com.jjt.ingsis.snippets.background.RemotePermit
import com.jjt.ingsis.snippets.execution.AuditedRun
import com.jjt.ingsis.snippets.execution.CompletedRun
import com.jjt.ingsis.snippets.execution.ExecutionSnapshot
import com.jjt.ingsis.snippets.language.execution.ExecutionDescriptor
import java.util.UUID

interface AutomaticRuns {
    fun begin(
        permit: RemotePermit,
        actorId: String,
        snapshot: ExecutionSnapshot,
    ): AuditedRun

    fun find(permitId: UUID): AuditedRun?

    fun bind(
        permitId: UUID,
        remote: ExecutionDescriptor,
    )

    fun complete(
        permitId: UUID,
        result: CompletedRun,
    )

    fun failed(
        permitId: UUID,
        reason: String,
        terminal: Boolean,
    )

    fun results(jobId: UUID): List<AuditedRun>
}
