package com.jjt.ingsis.snippets.language.printscript.execution

import com.jjt.ingsis.snippets.language.execution.ExecutionDescriptor
import com.jjt.ingsis.snippets.language.execution.ExecutionMode
import com.jjt.ingsis.snippets.language.execution.ExecutionRequest
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.language.execution.ExecutionState
import com.jjt.ingsis.snippets.language.execution.LanguageEventStream
import com.jjt.ingsis.snippets.language.execution.LanguageExecutor
import com.jjt.ingsis.snippets.language.printscript.PrintScriptProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
class PrintScriptExecutor(
    properties: PrintScriptProperties,
    execution: ExecutionClientProperties,
    mapper: ObjectMapper,
) : LanguageExecutor {
    private val transport = ExecutionHttpTransport(properties, mapper)
    private val stream = ExecutionEventStream(transport, execution)
    override val language: String = "printscript"
    override val supportedVersions: Set<String> = setOf("1.0", "1.1")

    override fun start(request: ExecutionRequest): ExecutionResult<ExecutionDescriptor> {
        if (request.version !in supportedVersions) return ExecutionResult.Rejected("unsupported-version")
        if (request.mode == ExecutionMode.INTERACTIVE &&
            request.inputs.isNotEmpty()
        ) {
            return ExecutionResult.Rejected("invalid-request")
        }
        val body =
            mapOf("code" to request.code, "version" to request.version, "mode" to request.mode) +
                (if (request.mode == ExecutionMode.TEST) mapOf("inputs" to request.inputs) else emptyMap())
        return transport.json(
            method = "POST",
            path = "/executions",
            body = body,
            expected = HttpStatus.CREATED.value(),
        ) {
            ExecutionProtocol.descriptor(it).also { descriptor ->
                if (descriptor.mode != request.mode) throw ExecutionProtocolFailure()
            }
        }
    }

    override fun describe(id: UUID): ExecutionResult<ExecutionDescriptor> =
        transport.json(method = "GET", path = "/executions/$id", body = null, expected = HttpStatus.OK.value()) {
            ExecutionProtocol.descriptor(it, id)
        }

    override fun openEvents(
        id: UUID,
        after: Long,
    ): ExecutionResult<LanguageEventStream> = stream.open(id, after)

    override fun input(
        id: UUID,
        requestId: UUID,
        value: String,
    ): ExecutionResult<Unit> =
        transport.json(
            method = "POST",
            path = "/executions/$id/inputs",
            body =
                mapOf(
                    "requestId" to requestId,
                    "value" to value,
                ),
            expected = HttpStatus.OK.value(),
        ) {
            if (ExecutionProtocol.uuid(ExecutionProtocol.text(it, "requestId")) != requestId ||
                !it.path("accepted").isBoolean ||
                !it.path("accepted").asBoolean()
            ) {
                throw ExecutionProtocolFailure()
            }
        }

    override fun cancel(id: UUID): ExecutionResult<ExecutionState> =
        transport.json(
            method = "POST",
            path = "/executions/$id/cancel",
            body = emptyMap<String, Any>(),
            expected = HttpStatus.ACCEPTED.value(),
        ) {
            ExecutionProtocol.state(ExecutionProtocol.text(it, "state")).also { state ->
                if (state != ExecutionState.CANCELLING &&
                    state !in ExecutionProtocol.terminalStates
                ) {
                    throw ExecutionProtocolFailure()
                }
            }
        }
}
