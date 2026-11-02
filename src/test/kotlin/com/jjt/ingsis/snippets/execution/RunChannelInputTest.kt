package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Instant
import java.util.UUID

class RunChannelInputTest {
    private val executor = ControlledExecutor()
    private val requestId = UUID.randomUUID()

    @Test
    fun `an interactive input reaches the interpreter with the session handle`() {
        executor.inputResult.set(ExecutionResult.Success(Unit))
        val channel = channel(interactiveSnapshot())

        assertThat(channel.input(requestId, "Ana")).isEqualTo(RunResult.Success(Unit))
        assertThat(executor.inputs).containsExactly(
            ControlledInput(checkNotNull(channel.run.remote).id, requestId, "Ana"),
        )
    }

    @Test
    fun `a test run never accepts input and nothing reaches the interpreter`() {
        val channel = channel(snapshot())

        assertThat(channel.input(requestId, "Ana")).isEqualTo(RunResult.Refused(RunFailure.INPUT_CONFLICT))
        assertThat(executor.inputs).isEmpty()
    }

    @Test
    fun `an input is limited to 8 KiB measured in UTF8 before reaching the interpreter`() {
        executor.inputResult.set(ExecutionResult.Success(Unit))
        val channel = channel(interactiveSnapshot())

        assertThat(
            channel.input(requestId, "ñ".repeat(4097)),
        ).isEqualTo(RunResult.Refused(RunFailure.PAYLOAD_TOO_LARGE))
        assertThat(channel.input(requestId, "ñ".repeat(4096))).isEqualTo(RunResult.Success(Unit))
        assertThat(executor.inputs).hasSize(1)
    }

    @Test
    fun `interpreter refusals and failures keep their meaning`() {
        val channel = channel(interactiveSnapshot())

        executor.inputResult.set(ExecutionResult.Rejected("input-conflict"))
        assertThat(channel.input(requestId, "Ana")).isEqualTo(RunResult.Refused(RunFailure.INPUT_CONFLICT))
        executor.inputResult.set(ExecutionResult.Failed("execution-service-unavailable", true))
        assertThat(channel.input(requestId, "Ana")).isEqualTo(RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE))
    }

    private fun channel(snapshot: ExecutionSnapshot): RunChannel {
        val remote = (executor.start(snapshot.request()) as ExecutionResult.Success).value
        val run =
            AuditedRun(
                id = UUID.randomUUID(),
                actorId = "actor",
                snapshot = snapshot,
                startedAt = Instant.now(),
                remote = remote,
            )
        return RunChannel(run, executor, mock(ExecutionAudit::class.java))
    }
}
