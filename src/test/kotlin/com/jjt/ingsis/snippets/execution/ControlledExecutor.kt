package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.language.execution.ExecutionDescriptor
import com.jjt.ingsis.snippets.language.execution.ExecutionEvent
import com.jjt.ingsis.snippets.language.execution.ExecutionPayload
import com.jjt.ingsis.snippets.language.execution.ExecutionRequest
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.language.execution.ExecutionState
import com.jjt.ingsis.snippets.language.execution.LanguageEventStream
import com.jjt.ingsis.snippets.language.execution.LanguageExecutor
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class ExecutionScript(
    val outputs: List<String> = listOf("", "ñ", "ñ"),
    val outcome: ExecutionState = ExecutionState.COMPLETED,
    val release: CountDownLatch = CountDownLatch(0),
)

internal data class ControlledInput(
    val id: UUID,
    val requestId: UUID,
    val value: String,
)

internal class ControlledExecutor : LanguageExecutor {
    override val language = "printscript"
    override val supportedVersions = setOf("1.0", "1.1")
    val requests = CopyOnWriteArrayList<ExecutionRequest>()
    val script = AtomicReference(ExecutionScript())
    val startFailure = AtomicReference<ExecutionResult<ExecutionDescriptor>?>()
    val openFailure = AtomicReference<String?>()
    val describeFailure = AtomicReference<ExecutionResult<ExecutionDescriptor>?>()
    val runs = ConcurrentHashMap<UUID, ControlledRun>()
    val inputs = CopyOnWriteArrayList<ControlledInput>()
    val inputResult = AtomicReference<ExecutionResult<Unit>>(ExecutionResult.Rejected("input-conflict"))

    override fun start(request: ExecutionRequest): ExecutionResult<ExecutionDescriptor> {
        requests.add(request)
        startFailure.get()?.let { return it }
        val run = ControlledRun(remote().copy(mode = request.mode), script.get())
        runs[run.descriptor.id] = run
        return ExecutionResult.Success(run.descriptor)
    }

    override fun describe(id: UUID): ExecutionResult<ExecutionDescriptor> {
        describeFailure.get()?.let { return it }
        val run = runs[id] ?: return ExecutionResult.Rejected("execution-not-found")
        return ExecutionResult.Success(run.descriptor.copy(state = run.state.get(), lastSeq = run.lastSeq()))
    }

    override fun openEvents(
        id: UUID,
        after: Long,
    ): ExecutionResult<LanguageEventStream> {
        val failure = openFailure.get()
        val run = runs[id]
        return when {
            failure != null -> ExecutionResult.Rejected(failure)
            run == null -> ExecutionResult.Rejected("execution-not-found")
            after > run.lastSeq() -> ExecutionResult.Rejected("invalid-cursor")
            !run.consumer.compareAndSet(false, true) -> ExecutionResult.Rejected("execution-consumer-conflict")
            else -> ExecutionResult.Success(ControlledEventStream(run, after))
        }
    }

    override fun input(
        id: UUID,
        requestId: UUID,
        value: String,
    ): ExecutionResult<Unit> {
        inputs.add(ControlledInput(id, requestId, value))
        return inputResult.get()
    }

    override fun cancel(id: UUID): ExecutionResult<ExecutionState> {
        val run = runs[id] ?: return ExecutionResult.Rejected("execution-not-found")
        if (run.state.get() !in terminalStates) run.state.set(ExecutionState.CANCELLING)
        return ExecutionResult.Success(run.state.get())
    }
}

internal class ControlledRun(
    val descriptor: ExecutionDescriptor,
    val script: ExecutionScript,
) {
    val state = AtomicReference(if (script.release.count == 0L) script.outcome else ExecutionState.RUNNING)
    val consumer = AtomicBoolean()

    fun lastSeq(): Long = if (script.release.count == 0L) script.outputs.size + 2L else script.outputs.size + 1L

    fun events(): List<ExecutionEvent> =
        listOf(ExecutionEvent(descriptor.id, 1, ExecutionPayload.Started(descriptor.mode))) +
            script.outputs.mapIndexed {
                index,
                text,
                ->
                ExecutionEvent(descriptor.id, index + 2L, ExecutionPayload.Output(text))
            } +
            ExecutionEvent(
                descriptor.id,
                script.outputs.size + 2L,
                ExecutionPayload.Terminal(
                    outcome = script.outcome,
                    reason = "controlled-terminal",
                    diagnostics = emptyList(),
                    unusedInputs = 0,
                ),
            )
}

private class ControlledEventStream(
    private val run: ControlledRun,
    private val after: Long,
) : LanguageEventStream {
    override fun read(consume: (ExecutionEvent) -> Unit): ExecutionResult<Unit> =
        try {
            run.events().filter { it.seq > after }.forEach { event -> consume(ready(event)) }
            ExecutionResult.Success(Unit)
        } catch (_: IOException) {
            ExecutionResult.Failed("execution-stream-interrupted", true)
        } finally {
            close()
        }

    private fun ready(event: ExecutionEvent): ExecutionEvent {
        if (event.payload !is ExecutionPayload.Terminal) return event
        check(run.script.release.await(5, TimeUnit.SECONDS)) { "Test did not release its terminal" }
        val outcome = if (run.state.get() == ExecutionState.CANCELLING) ExecutionState.CANCELLED else run.script.outcome
        run.state.set(outcome)
        return event.copy(
            payload =
                ExecutionPayload.Terminal(
                    outcome = outcome,
                    reason = "controlled-terminal",
                    diagnostics = emptyList(),
                    unusedInputs = 0,
                ),
        )
    }

    override fun close() {
        run.consumer.set(false)
    }
}
