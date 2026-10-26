package com.jjt.ingsis.snippets.language.printscript.execution

import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.language.execution.ExecutionDescriptor
import com.jjt.ingsis.snippets.language.execution.ExecutionEvent
import com.jjt.ingsis.snippets.language.execution.ExecutionMode
import com.jjt.ingsis.snippets.language.execution.ExecutionPayload
import com.jjt.ingsis.snippets.language.execution.ExecutionState
import tools.jackson.databind.JsonNode
import java.util.UUID

internal class ExecutionProtocolFailure : RuntimeException()

internal object ExecutionProtocol {
    val terminalStates =
        setOf(
            ExecutionState.COMPLETED,
            ExecutionState.PROGRAM_ERROR,
            ExecutionState.INPUT_EXHAUSTED,
            ExecutionState.LIMIT_EXCEEDED,
            ExecutionState.CANCELLED,
            ExecutionState.TECHNICAL_FAILURE,
            ExecutionState.INTERRUPTED,
        )

    fun descriptor(
        node: JsonNode,
        expectedId: UUID? = null,
    ): ExecutionDescriptor {
        val id = uuid(text(node, "executionId"))
        if (expectedId != null && id != expectedId) fail()
        val limits = node.path("limits")
        val required =
            setOf(
                "activeGlobal",
                "totalSeconds",
                "inputWaitSeconds",
                "reconnectSeconds",
                "codeBytes",
                "inputBytes",
                "outputBytes",
                "bufferEvents",
                "bufferBytes",
                "terminalRetentionSeconds",
            )
        if (!limits.isObject || limits.properties().map { it.key }.toSet() != required) fail()
        val mapped = limits.properties().associate { it.key to positive(it.value) }
        return ExecutionDescriptor(
            id = id,
            mode = mode(text(node, "mode")),
            state = state(text(node, "state")),
            limits = mapped,
            lastSeq = nonnegative(node.path("lastSeq")),
        )
    }

    fun event(
        node: JsonNode,
        id: UUID,
        expectedSeq: Long,
    ): ExecutionEvent {
        if (uuid(text(node, "executionId")) != id || positive(node.path("seq")) != expectedSeq) fail()
        val data = node.path("data")
        if (!data.isObject) fail()
        val payload =
            when (text(node, "type")) {
                "started" -> ExecutionPayload.Started(mode(text(data, "mode")))
                "output" -> ExecutionPayload.Output(text(data, "text"))
                "input-required" -> ExecutionPayload.InputRequired(uuid(text(data, "requestId")), text(data, "prompt"))
                "input-accepted" -> ExecutionPayload.InputAccepted(uuid(text(data, "requestId")))
                "heartbeat" -> ExecutionPayload.Heartbeat
                "terminal" -> terminal(data)
                else -> fail()
            }
        if ((expectedSeq == 1L) != (payload is ExecutionPayload.Started)) fail()
        return ExecutionEvent(id, expectedSeq, payload)
    }

    private fun terminal(node: JsonNode): ExecutionPayload.Terminal {
        val outcome = state(text(node, "outcome"))
        if (outcome !in terminalStates || text(node, "reason").isBlank()) fail()
        val diagnostics = node.path("diagnostics")
        if (!diagnostics.isMissingNode && !diagnostics.isArray) fail()
        val mapped =
            diagnostics
                .asSequence()
                .map {
                    Diagnostic(
                        rule = text(it, "rule"),
                        message = text(it, "message"),
                        line = positiveInt(it.path("line")),
                        column = positiveInt(it.path("column")),
                    )
                }.toList()
        val unused = node.path("unusedInputs").takeUnless { it.isMissingNode }?.let { nonnegative(it).toInt() }
        return ExecutionPayload.Terminal(
            outcome = outcome,
            reason = text(node, "reason"),
            diagnostics = mapped,
            unusedInputs = unused,
        )
    }

    fun text(
        node: JsonNode,
        field: String,
    ): String = node.path(field).let { if (it.isString) it.asString() else fail() }

    fun uuid(value: String): UUID {
        if (!value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) fail()
        return UUID.fromString(value)
    }

    fun state(value: String): ExecutionState = ExecutionState.entries.find { it.name == value } ?: fail()

    fun mode(value: String): ExecutionMode = ExecutionMode.entries.find { it.name == value } ?: fail()

    private fun positive(node: JsonNode): Long = nonnegative(node).also { if (it == 0L) fail() }

    private fun positiveInt(node: JsonNode): Int = positive(node).also { if (it > Int.MAX_VALUE) fail() }.toInt()

    private fun nonnegative(node: JsonNode): Long {
        if (!node.isIntegralNumber || !node.canConvertToLong() || node.asLong() < 0) fail()
        return node.asLong()
    }

    private fun fail(): Nothing = throw ExecutionProtocolFailure()
}
