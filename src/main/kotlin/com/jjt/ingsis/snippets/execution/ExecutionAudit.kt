package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.language.execution.ExecutionDescriptor
import com.jjt.ingsis.snippets.language.execution.ExecutionPayload
import java.time.Instant
import java.util.UUID

enum class Comparison { PASSED, FAILED }

data class CompletedRun(
    val outputs: List<String>,
    val terminal: ExecutionPayload.Terminal,
    val comparison: Comparison?,
    val lastSeq: Long,
)

data class AuditedRun(
    val id: UUID,
    val actorId: String,
    val snapshot: ExecutionSnapshot,
    val startedAt: Instant,
    val remote: ExecutionDescriptor? = null,
    val completed: CompletedRun? = null,
    val finishedAt: Instant? = null,
    val failure: String? = null,
    val reserved: Boolean = true,
)

interface ExecutionAudit {
    fun reserve(run: AuditedRun): Boolean

    fun bind(
        id: UUID,
        descriptor: ExecutionDescriptor,
    )

    fun complete(
        id: UUID,
        result: CompletedRun,
    )

    fun unknown(
        id: UUID,
        reason: String,
        release: Boolean,
    )

    fun reject(
        id: UUID,
        reason: String,
    )

    fun find(id: UUID): AuditedRun?

    fun reserved(): List<AuditedRun>
}
