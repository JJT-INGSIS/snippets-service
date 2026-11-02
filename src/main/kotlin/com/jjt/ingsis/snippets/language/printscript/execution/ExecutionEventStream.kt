package com.jjt.ingsis.snippets.language.printscript.execution

import com.jjt.ingsis.snippets.language.execution.ExecutionEvent
import com.jjt.ingsis.snippets.language.execution.ExecutionPayload
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.language.execution.LanguageEventStream
import org.springframework.http.HttpStatus
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class ExecutionEventStream(
    private val transport: ExecutionHttpTransport,
    private val properties: ExecutionClientProperties,
) {
    fun open(
        id: UUID,
        after: Long,
    ): ExecutionResult<LanguageEventStream> {
        if (after < 0 || after == Long.MAX_VALUE) return ExecutionResult.Rejected("invalid-cursor")
        return transport.attempt(uncertain = true) {
            val response = transport.send("GET", "/executions/$id/events?after=$after", null)
            val contentType = response.headers().firstValue("Content-Type").orElse("")
            if (response.statusCode() != HttpStatus.OK.value()) {
                timedExecutionBody(response.body(), properties.streamTimeout) { input ->
                    val bytes = input.readNBytes(ExecutionHttpTransport.MAX_JSON_BYTES + 1)
                    if (bytes.size > ExecutionHttpTransport.MAX_JSON_BYTES) throw ExecutionProtocolFailure()
                    transport.rejection(response.statusCode(), contentType, transport.mapper.readTree(bytes))
                }
            } else if (contentType.substringBefore(';').trim() != "application/x-ndjson") {
                response.body().close()
                throw ExecutionProtocolFailure()
            } else {
                ExecutionResult.Success(
                    OpenedExecutionEvents(
                        input = response.body(),
                        timeout = properties.streamTimeout,
                        transport = transport,
                        processor = transport.processorOf(response),
                    ) { input, consume ->
                        decode(input = input, id = id, after = after, consume = consume)
                    },
                )
            }
        }
    }

    private fun decode(
        input: InputStream,
        id: UUID,
        after: Long,
        consume: (ExecutionEvent) -> Unit,
    ): ExecutionResult<Unit> {
        val sequence = AtomicLong(after)
        val terminal = AtomicBoolean()
        val buffered = BufferedInputStream(input)
        while (true) {
            val line = line(buffered) ?: break
            if (terminal.get()) throw ExecutionProtocolFailure()
            val event = ExecutionProtocol.event(transport.mapper.readTree(line), id, sequence.incrementAndGet())
            terminal.set(event.payload is ExecutionPayload.Terminal)
            consume(event)
        }
        return if (terminal.get()) {
            ExecutionResult.Success(
                Unit,
            )
        } else {
            ExecutionResult.Failed("execution-stream-interrupted", uncertain = true)
        }
    }

    private fun line(input: InputStream): ByteArray? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            when {
                next == -1 && bytes.size() == 0 -> {
                    return null
                }

                next == -1 || bytes.size() >= MAX_EVENT_BYTES -> {
                    throw ExecutionProtocolFailure()
                }

                next == '\n'.code -> {
                    if (bytes.size() == 0) throw ExecutionProtocolFailure()
                    return bytes.toByteArray()
                }

                else -> {
                    bytes.write(next)
                }
            }
        }
    }

    companion object {
        private const val MAX_EVENT_BYTES = 327_680
    }
}
