package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.language.execution.ExecutionEvent
import com.jjt.ingsis.snippets.language.execution.ExecutionPayload
import com.jjt.ingsis.snippets.language.execution.ExecutionState
import java.util.UUID

internal val terminalStates =
    setOf(
        ExecutionState.COMPLETED,
        ExecutionState.PROGRAM_ERROR,
        ExecutionState.INPUT_EXHAUSTED,
        ExecutionState.LIMIT_EXCEEDED,
        ExecutionState.CANCELLED,
        ExecutionState.TECHNICAL_FAILURE,
        ExecutionState.INTERRUPTED,
    )

data class TestRunProgress(
    val lastSeq: Long = 0,
    val outputs: List<String> = emptyList(),
    val outputBytes: Long = 0,
    val state: ExecutionState = ExecutionState.RUNNING,
    val pendingInput: UUID? = null,
    val completed: CompletedRun? = null,
) {
    fun advance(
        event: ExecutionEvent,
        snapshot: ExecutionSnapshot,
        maximumOutputBytes: Long,
    ): RunResult<TestRunProgress> {
        if (event.seq <= lastSeq) return RunResult.Success(this)
        if (!validNext(event)) return invalid()
        val next = copy(lastSeq = event.seq)
        return when (val payload = event.payload) {
            is ExecutionPayload.Started -> {
                if (payload.mode == snapshot.mode()) RunResult.Success(next) else invalid()
            }

            is ExecutionPayload.Output -> {
                next.appendOutput(payload.text, snapshot, maximumOutputBytes)
            }

            is ExecutionPayload.InputRequired -> {
                next.awaitInput(payload.requestId, snapshot)
            }

            is ExecutionPayload.InputAccepted -> {
                next.acceptInput(payload.requestId)
            }

            is ExecutionPayload.Terminal -> {
                next.finish(payload, snapshot)
            }

            ExecutionPayload.Heartbeat -> {
                RunResult.Success(next)
            }
        }
    }

    private fun validNext(event: ExecutionEvent): Boolean =
        completed == null && event.seq == lastSeq + 1 &&
            (event.seq == 1L) == (event.payload is ExecutionPayload.Started)

    private fun appendOutput(
        text: String,
        snapshot: ExecutionSnapshot,
        maximum: Long,
    ): RunResult<TestRunProgress> {
        val bytes = outputBytes + text.toByteArray(Charsets.UTF_8).size
        val retained = if (snapshot.test == null) outputs else outputs + text
        return if (bytes > maximum) invalid() else RunResult.Success(copy(outputs = retained, outputBytes = bytes))
    }

    private fun awaitInput(
        requestId: UUID,
        snapshot: ExecutionSnapshot,
    ): RunResult<TestRunProgress> =
        if (snapshot.test == null && pendingInput == null) {
            RunResult.Success(copy(state = ExecutionState.WAITING_INPUT, pendingInput = requestId))
        } else {
            invalid()
        }

    private fun acceptInput(requestId: UUID): RunResult<TestRunProgress> =
        if (pendingInput == requestId) {
            RunResult.Success(copy(state = ExecutionState.RUNNING, pendingInput = null))
        } else {
            invalid()
        }

    private fun finish(
        terminal: ExecutionPayload.Terminal,
        snapshot: ExecutionSnapshot,
    ): RunResult<TestRunProgress> {
        if (terminal.outcome !in terminalStates) return invalid()
        val test = snapshot.test
        val comparison = if (test != null && terminal.outcome == ExecutionState.COMPLETED) compare(test) else null
        val result = CompletedRun(outputs = outputs, terminal = terminal, comparison = comparison, lastSeq = lastSeq)
        return RunResult.Success(copy(state = terminal.outcome, pendingInput = null, completed = result))
    }

    private fun compare(test: TestSnapshot): Comparison =
        if (outputs == test.definition.expectedOutputs) Comparison.PASSED else Comparison.FAILED

    private fun invalid(): RunResult.Refused = RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
}
