package com.jjt.ingsis.snippets.language.operations

import com.jjt.ingsis.snippets.language.Diagnostic
import java.time.Instant
import java.util.UUID

enum class ProcessingKind { LINT, FORMAT }

enum class ProcessingState { PENDING, RUNNING, CANCELLING, COMPLETED, FAILED, CANCELLED }

data class ProcessingCommand(
    val kind: ProcessingKind,
    val version: String,
    val code: String,
    val rules: Map<String, Any?>,
    val processorRevision: String,
    val submittedAt: Instant,
)

data class ProcessingViolation(
    val rule: String,
    val reason: String,
)

data class ProcessingOutput(
    val outcome: String,
    val code: String? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
    val violations: List<ProcessingViolation> = emptyList(),
)

data class ProcessingOperation(
    val id: UUID,
    val command: ProcessingCommand?,
    val state: ProcessingState,
    val output: ProcessingOutput? = null,
    val reason: String? = null,
    val owner: UUID? = null,
)

sealed interface ProcessingReply {
    data class Found(
        val operation: ProcessingOperation,
        val activeLimit: Long?,
    ) : ProcessingReply

    data object Missing : ProcessingReply

    data object Unavailable : ProcessingReply

    data class Refused(
        val reason: String,
    ) : ProcessingReply
}

interface LanguageOperations {
    fun start(
        id: UUID,
        command: ProcessingCommand,
    ): ProcessingReply

    fun find(id: UUID): ProcessingReply

    fun cancel(id: UUID): ProcessingReply
}
