package com.jjt.ingsis.snippets.language.printscript.execution

import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.language.execution.ExecutionEvent
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.language.execution.LanguageEventStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the opened HTTP resource, including a deadline if consumption never starts. */
internal class OpenedExecutionEvents(
    private val input: InputStream,
    timeout: Duration,
    private val transport: ExecutionHttpTransport,
    override val processor: ProcessorRevision?,
    private val decode: (InputStream, (ExecutionEvent) -> Unit) -> ExecutionResult<Unit>,
) : LanguageEventStream {
    private val consumed = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val deadline =
        timer.schedule({
            try {
                input.close()
            } catch (_: IOException) {
                // Connection already closed.
            } finally {
                timer.shutdown()
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS)

    override fun read(consume: (ExecutionEvent) -> Unit): ExecutionResult<Unit> {
        if (!consumed.compareAndSet(false, true)) return ExecutionResult.Rejected("execution-consumer-conflict")
        return try {
            transport.attempt(uncertain = true) { decode(input, consume) }
        } finally {
            close()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            deadline.cancel(false)
            timer.shutdownNow()
            try {
                input.close()
            } catch (_: IOException) {
                // The connection is already gone.
            }
        }
    }
}
