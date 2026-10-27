package com.jjt.ingsis.snippets.language.execution

import com.jjt.ingsis.snippets.language.Diagnostic
import java.util.UUID

enum class ExecutionMode { TEST, INTERACTIVE }

enum class ExecutionState {
    RUNNING,
    WAITING_INPUT,
    CANCELLING,
    COMPLETED,
    PROGRAM_ERROR,
    INPUT_EXHAUSTED,
    LIMIT_EXCEEDED,
    CANCELLED,
    TECHNICAL_FAILURE,
    INTERRUPTED,
}

data class ExecutionRequest(
    val code: String,
    val version: String,
    val mode: ExecutionMode,
    val inputs: List<String> = emptyList(),
)

// This is an internal Language handle, never a public API/session association or authorization token.
data class ExecutionDescriptor(
    val id: UUID,
    val mode: ExecutionMode,
    val state: ExecutionState,
    val limits: Map<String, Long>,
    val lastSeq: Long,
)

data class ExecutionEvent(
    val id: UUID,
    val seq: Long,
    val payload: ExecutionPayload,
)

sealed interface ExecutionPayload {
    data class Started(
        val mode: ExecutionMode,
    ) : ExecutionPayload

    data class Output(
        val text: String,
    ) : ExecutionPayload

    data class InputRequired(
        val requestId: UUID,
        val prompt: String,
    ) : ExecutionPayload

    data class InputAccepted(
        val requestId: UUID,
    ) : ExecutionPayload

    data object Heartbeat : ExecutionPayload

    data class Terminal(
        val outcome: ExecutionState,
        val reason: String,
        val diagnostics: List<Diagnostic>,
        val unusedInputs: Int?,
    ) : ExecutionPayload
}

sealed interface ExecutionResult<out T> {
    data class Success<T>(
        val value: T,
    ) : ExecutionResult<T>

    data class Rejected(
        val reason: String,
    ) : ExecutionResult<Nothing>

    data class Failed(
        val reason: String,
        val uncertain: Boolean = false,
    ) : ExecutionResult<Nothing>
}

interface LanguageExecutor {
    val language: String
    val supportedVersions: Set<String>

    fun start(request: ExecutionRequest): ExecutionResult<ExecutionDescriptor>

    fun describe(id: UUID): ExecutionResult<ExecutionDescriptor>

    fun events(
        id: UUID,
        after: Long,
        consume: (ExecutionEvent) -> Unit,
    ): ExecutionResult<Unit>

    fun input(
        id: UUID,
        requestId: UUID,
        value: String,
    ): ExecutionResult<Unit>

    fun cancel(id: UUID): ExecutionResult<ExecutionState>
}
