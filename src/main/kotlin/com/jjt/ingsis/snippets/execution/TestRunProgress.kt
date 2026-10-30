package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.language.execution.ExecutionEvent
import com.jjt.ingsis.snippets.language.execution.ExecutionMode
import com.jjt.ingsis.snippets.language.execution.ExecutionPayload
import com.jjt.ingsis.snippets.language.execution.ExecutionState

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
                if (payload.mode ==
                    ExecutionMode.TEST
                ) {
                    RunResult.Success(next)
                } else {
                    invalid()
                }
            }

            is ExecutionPayload.Output -> {
                next.appendOutput(payload.text, maximumOutputBytes)
            }

            is ExecutionPayload.Terminal -> {
                next.finish(payload, snapshot)
            }

            ExecutionPayload.Heartbeat -> {
                RunResult.Success(next)
            }

            is ExecutionPayload.InputRequired, is ExecutionPayload.InputAccepted -> {
                invalid()
            }
        }
    }

    private fun validNext(event: ExecutionEvent): Boolean =
        completed == null && event.seq == lastSeq + 1 &&
            (event.seq == 1L) == (event.payload is ExecutionPayload.Started)

    private fun appendOutput(
        text: String,
        maximum: Long,
    ): RunResult<TestRunProgress> {
        val bytes = outputBytes + text.toByteArray(Charsets.UTF_8).size
        return if (bytes >
            maximum
        ) {
            invalid()
        } else {
            RunResult.Success(copy(outputs = outputs + text, outputBytes = bytes))
        }
    }

    private fun finish(
        terminal: ExecutionPayload.Terminal,
        snapshot: ExecutionSnapshot,
    ): RunResult<TestRunProgress> {
        if (terminal.outcome !in terminalStates) return invalid()
        val comparison = if (terminal.outcome == ExecutionState.COMPLETED) compare(snapshot) else null
        val result = CompletedRun(outputs = outputs, terminal = terminal, comparison = comparison, lastSeq = lastSeq)
        return RunResult.Success(copy(state = terminal.outcome, completed = result))
    }

    private fun compare(snapshot: ExecutionSnapshot): Comparison =
        if (outputs == snapshot.definition.expectedOutputs) Comparison.PASSED else Comparison.FAILED

    private fun invalid(): RunResult.Refused = RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
}
