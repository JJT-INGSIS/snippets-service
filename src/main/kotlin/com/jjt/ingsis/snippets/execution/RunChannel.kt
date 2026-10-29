package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.language.execution.ExecutionEvent
import com.jjt.ingsis.snippets.language.execution.ExecutionPayload
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.language.execution.ExecutionState
import com.jjt.ingsis.snippets.language.execution.LanguageEventStream
import com.jjt.ingsis.snippets.language.execution.LanguageExecutor
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RunChannel(
    val run: AuditedRun,
    val executor: LanguageExecutor,
    private val audit: ExecutionAudit,
) {
    private val progress =
        AtomicReference(
            run.completed?.let {
                TestRunProgress(
                    lastSeq = it.lastSeq,
                    outputs = it.outputs,
                    outputBytes = it.outputs.sumOf { text -> text.toByteArray(Charsets.UTF_8).size.toLong() },
                    state = it.terminal.outcome,
                    completed = it,
                )
            }
                ?: TestRunProgress(),
        )
    private val consumer = AtomicBoolean()
    private val opened = AtomicReference<RunEventStream?>()
    private val connectedAt = AtomicReference<Instant?>()
    private val finishedAt = AtomicReference(run.finishedAt)

    fun progress(): TestRunProgress = progress.get()

    fun connected(): Boolean = consumer.get()

    fun open(after: Long): RunResult<RunEventStream> {
        if (after < 0 || after == Long.MAX_VALUE) return RunResult.Refused(RunFailure.INVALID_CURSOR)
        if (!consumer.compareAndSet(false, true)) return RunResult.Refused(RunFailure.CONSUMER_CONFLICT)
        // Rebuild lost in-memory outputs before resuming the browser's cursor.
        return try {
            when (val remote = source(after)) {
                is ExecutionResult.Success -> {
                    val stream = RunEventStream(this, remote.value, after)
                    connectedAt.set(Instant.now())
                    opened.set(stream)
                    RunResult.Success(stream)
                }

                is ExecutionResult.Rejected -> {
                    RunResult.Refused(executionFailure(remote.reason))
                }

                is ExecutionResult.Failed -> {
                    RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
                }
            }
        } finally {
            if (opened.get() == null) consumer.set(false)
        }
    }

    private fun source(after: Long): ExecutionResult<LanguageEventStream> {
        val completed = progress.get().completed
        if (completed != null && after >= completed.lastSeq - 1) {
            val terminal =
                if (after < completed.lastSeq) {
                    ExecutionEvent(checkNotNull(run.remote).id, completed.lastSeq, completed.terminal)
                } else {
                    null
                }
            return ExecutionResult.Success(CachedTerminalStream(terminal))
        }
        val replayFrom = minOf(after, progress.get().lastSeq)
        val opened = executor.openEvents(checkNotNull(run.remote).id, replayFrom)
        return if (completed != null && opened is ExecutionResult.Rejected && opened.reason == "execution-not-found") {
            ExecutionResult.Rejected("event-history-unavailable")
        } else {
            opened
        }
    }

    internal fun accept(event: ExecutionEvent): RunResult<RunEvent> {
        val remote = checkNotNull(run.remote)
        if (event.id != remote.id) return RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
        return when (val next = progress.get().advance(event, run.snapshot, remote.limits.getValue("outputBytes"))) {
            is RunResult.Refused -> {
                next
            }

            is RunResult.Success -> {
                val result = next.value.completed
                if (result != null && progress.get().completed == null) {
                    audit.complete(run.id, result)
                    finishedAt.set(Instant.now())
                }
                progress.set(next.value)
                RunResult.Success(RunEvent(event.seq, event.payload, result?.comparison))
            }
        }
    }

    fun cancel(): ExecutionResult<ExecutionState> {
        val result = executor.cancel(checkNotNull(run.remote).id)
        if (result is ExecutionResult.Success && progress.get().completed == null) {
            progress.updateAndGet { it.copy(state = result.value) }
        }
        return result
    }

    fun closeExpired(now: Instant) {
        val since = connectedAt.get() ?: return
        val maximum =
            checkNotNull(run.remote).limits.getValue("totalSeconds") +
                run.remote.limits.getValue("reconnectSeconds")
        if (now.isAfter(since.plusSeconds(maximum))) opened.get()?.close()
    }

    fun expired(now: Instant): Boolean =
        finishedAt.get()?.let {
            now.isAfter(it.plusSeconds(checkNotNull(run.remote).limits.getValue("terminalRetentionSeconds")))
        } ?: false

    internal fun disconnect(stream: RunEventStream) {
        if (opened.compareAndSet(stream, null)) {
            connectedAt.set(null)
            consumer.set(false)
        }
    }
}

private class CachedTerminalStream(
    private val event: ExecutionEvent?,
) : LanguageEventStream {
    private val available = AtomicBoolean(true)

    override fun read(consume: (ExecutionEvent) -> Unit): ExecutionResult<Unit> {
        if (!available.compareAndSet(true, false)) return ExecutionResult.Rejected("execution-consumer-conflict")
        event?.let(consume)
        return ExecutionResult.Success(Unit)
    }

    override fun close() {
        available.set(false)
    }
}

data class RunEvent(
    val seq: Long,
    val payload: ExecutionPayload,
    val comparison: Comparison? = null,
)

class RunEventStream internal constructor(
    private val channel: RunChannel,
    private val remote: LanguageEventStream,
    val after: Long,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    fun read(consume: (RunEvent) -> Unit): RunResult<Unit> =
        try {
            when (
                val result =
                    remote.read { event ->
                        when (val accepted = channel.accept(event)) {
                            is RunResult.Success -> if (accepted.value.seq > after) consume(accepted.value)
                            is RunResult.Refused -> throw IOException("Invalid execution stream")
                        }
                    }
            ) {
                is ExecutionResult.Success -> RunResult.Success(Unit)
                is ExecutionResult.Rejected -> RunResult.Refused(executionFailure(result.reason))
                is ExecutionResult.Failed -> RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
            }
        } catch (_: MetadataPersistenceException) {
            // No success terminal is delivered if its durable result could not be saved.
            RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
        } finally {
            close()
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                remote.close()
            } finally {
                channel.disconnect(this)
            }
        }
    }
}
