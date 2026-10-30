package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.language.execution.ExecutionEvent
import com.jjt.ingsis.snippets.language.execution.ExecutionMode
import com.jjt.ingsis.snippets.language.execution.ExecutionPayload
import com.jjt.ingsis.snippets.language.execution.ExecutionState
import com.jjt.ingsis.snippets.metadata.JsonSnapshotFingerprint
import com.jjt.ingsis.snippets.testcase.TestCaseDefinition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class TestRunProgressTest {
    private val id = UUID.randomUUID()

    @Test
    fun `exact comparison preserves empty duplicate and ordered outputs without mutating previous state`() {
        val definition = snapshot()
        val started =
            advance(
                previous = TestRunProgress(),
                seq = 1,
                payload = ExecutionPayload.Started(ExecutionMode.TEST),
                definition = definition,
            )
        val output =
            checkNotNull(definition.test).definition.expectedOutputs.fold(started) { previous, text ->
                advance(
                    previous = previous,
                    seq = previous.lastSeq + 1,
                    payload = ExecutionPayload.Output(text),
                    definition = definition,
                )
            }
        val terminal =
            advance(
                previous = output,
                seq = 5,
                payload = terminal(),
                definition = definition,
            )
        assertThat(started.outputs).isEmpty()
        assertThat(output.outputs).containsExactly("", "ñ", "ñ")
        assertThat(terminal.completed?.comparison).isEqualTo(Comparison.PASSED)
    }

    @Test
    fun `differences in spaces order count or unicode normalization fail and an empty test can pass`() {
        for (expected in listOf(listOf("ñ "), listOf("n\u0303"), listOf("ñ", "ñ"), emptyList())) {
            val definition = snapshot(expected)
            val started =
                advance(
                    previous = TestRunProgress(),
                    seq = 1,
                    payload = ExecutionPayload.Started(ExecutionMode.TEST),
                    definition = definition,
                )
            val output =
                advance(
                    previous = started,
                    seq = 2,
                    payload = ExecutionPayload.Output("ñ"),
                    definition = definition,
                )
            assertThat(
                advance(
                    previous = output,
                    seq = 3,
                    payload = terminal(),
                    definition = definition,
                ).completed?.comparison,
            ).isEqualTo(Comparison.FAILED)
        }
        val empty = snapshot(emptyList())
        val started =
            advance(
                previous = TestRunProgress(),
                seq = 1,
                payload = ExecutionPayload.Started(ExecutionMode.TEST),
                definition = empty,
            )
        assertThat(
            advance(
                previous = started,
                seq = 2,
                payload = terminal(),
                definition = empty,
            ).completed?.comparison,
        ).isEqualTo(Comparison.PASSED)
    }

    @Test
    fun `non completed programs never produce an assertion result`() {
        for (state in terminalStates - ExecutionState.COMPLETED) {
            val definition = snapshot()
            val started =
                advance(
                    previous = TestRunProgress(),
                    seq = 1,
                    payload = ExecutionPayload.Started(ExecutionMode.TEST),
                    definition = definition,
                )
            assertThat(
                advance(
                    previous = started,
                    seq = 2,
                    payload = terminal(state),
                    definition = definition,
                ).completed?.comparison,
            ).isNull()
        }
    }

    @Test
    fun `deduplicates replay and rejects a gap test input requests and excess UTF8 output`() {
        val definition = snapshot()
        val started =
            advance(
                previous = TestRunProgress(),
                seq = 1,
                payload = ExecutionPayload.Started(ExecutionMode.TEST),
                definition = definition,
            )
        assertThat(started.advance(ExecutionEvent(id, 1, ExecutionPayload.Started(ExecutionMode.TEST)), definition, 1))
            .isEqualTo(RunResult.Success(started))
        assertThat(
            started.advance(ExecutionEvent(id, 3, ExecutionPayload.Output("x")), definition, 3),
        ).isInstanceOf(RunResult.Refused::class.java)
        assertThat(
            started.advance(ExecutionEvent(id, 2, ExecutionPayload.Output("ñ")), definition, 1),
        ).isInstanceOf(RunResult.Refused::class.java)
        assertThat(
            started.advance(
                ExecutionEvent(id, 2, ExecutionPayload.InputRequired(UUID.randomUUID(), "?")),
                definition,
                3,
            ),
        ).isInstanceOf(RunResult.Refused::class.java)
    }

    @Test
    fun `an interactive run waits for one input at a time and relays output without keeping it`() {
        val interactive = interactiveSnapshot()
        val request = UUID.randomUUID()
        val started =
            advance(
                previous = TestRunProgress(),
                seq = 1,
                payload = ExecutionPayload.Started(ExecutionMode.INTERACTIVE),
                definition = interactive,
            )
        val asked =
            advance(
                previous = started,
                seq = 2,
                payload = ExecutionPayload.Output("¿Nombre?"),
                definition = interactive,
            )
        val waiting =
            advance(
                previous = asked,
                seq = 3,
                payload = ExecutionPayload.InputRequired(request, "Nombre"),
                definition = interactive,
            )
        val accepted =
            advance(
                previous = waiting,
                seq = 4,
                payload = ExecutionPayload.InputAccepted(request),
                definition = interactive,
            )
        val finished =
            advance(
                previous = accepted,
                seq = 5,
                payload = terminal(),
                definition = interactive,
            )

        assertThat(asked.outputs).isEmpty()
        assertThat(asked.outputBytes).isEqualTo(9)
        assertThat(waiting.state).isEqualTo(ExecutionState.WAITING_INPUT)
        assertThat(waiting.pendingInput).isEqualTo(request)
        assertThat(accepted.state).isEqualTo(ExecutionState.RUNNING)
        assertThat(accepted.pendingInput).isNull()
        assertThat(finished.completed?.outputs).isEmpty()
        assertThat(finished.completed?.comparison).isNull()
    }

    @Test
    fun `input events must match the mode the pending request and the output limit`() {
        val interactive = interactiveSnapshot()
        val started =
            advance(
                previous = TestRunProgress(),
                seq = 1,
                payload = ExecutionPayload.Started(ExecutionMode.INTERACTIVE),
                definition = interactive,
            )
        val waiting =
            advance(
                previous = started,
                seq = 2,
                payload = ExecutionPayload.InputRequired(UUID.randomUUID(), "?"),
                definition = interactive,
            )

        assertThat(TestRunProgress().attempt(1, ExecutionPayload.Started(ExecutionMode.TEST), interactive))
            .isInstanceOf(RunResult.Refused::class.java)
        assertThat(TestRunProgress().attempt(1, ExecutionPayload.Started(ExecutionMode.INTERACTIVE), snapshot()))
            .isInstanceOf(RunResult.Refused::class.java)
        assertThat(started.attempt(2, ExecutionPayload.InputAccepted(UUID.randomUUID()), interactive))
            .isInstanceOf(RunResult.Refused::class.java)
        assertThat(waiting.attempt(3, ExecutionPayload.InputAccepted(UUID.randomUUID()), interactive))
            .isInstanceOf(RunResult.Refused::class.java)
        assertThat(waiting.attempt(3, ExecutionPayload.InputRequired(UUID.randomUUID(), "?"), interactive))
            .isInstanceOf(RunResult.Refused::class.java)
        assertThat(started.advance(ExecutionEvent(id, 2, ExecutionPayload.Output("ñ")), interactive, 1))
            .isInstanceOf(RunResult.Refused::class.java)
    }

    @Test
    fun `fingerprints match the approved canonical UTF8 JSON vectors`() {
        val fingerprint = JsonSnapshotFingerprint()
        assertThat(fingerprint.code("printscript", "1.1", "println(1);"))
            .isEqualTo("sha256-v1:d36f4e10154d0ba0e271496ff499add84339de6e586ad4b4088f343c1d796b58")
        assertThat(fingerprint.code("printscript", "1.1", "println(\"ñ\");"))
            .isEqualTo("sha256-v1:db1223024faaec1cede79e159fd1ef5f563a490507fad288c7ec0eadb71865d4")
        assertThat(fingerprint.test(TestCaseDefinition(emptyList(), listOf("", "ñ", "ñ"))))
            .isEqualTo("sha256-v1:5a06757185434df7e1a5598d9b782415c93cafe132900d299edc2c0d14779bd6")
    }

    private fun terminal(state: ExecutionState = ExecutionState.COMPLETED) =
        ExecutionPayload.Terminal(
            outcome = state,
            reason = "test-reason",
            diagnostics = emptyList(),
            unusedInputs = 0,
        )

    private fun advance(
        previous: TestRunProgress,
        seq: Long,
        payload: ExecutionPayload,
        definition: ExecutionSnapshot,
    ): TestRunProgress =
        (previous.advance(ExecutionEvent(id, seq, payload), definition, 1048576) as RunResult.Success).value

    private fun TestRunProgress.attempt(
        seq: Long,
        payload: ExecutionPayload,
        snapshot: ExecutionSnapshot,
    ): RunResult<TestRunProgress> = advance(ExecutionEvent(id, seq, payload), snapshot, 1048576)
}
