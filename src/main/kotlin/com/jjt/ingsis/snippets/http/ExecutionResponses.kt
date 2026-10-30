package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.execution.AuditedRun
import com.jjt.ingsis.snippets.execution.RunEvent
import com.jjt.ingsis.snippets.execution.RunFailure
import com.jjt.ingsis.snippets.language.execution.ExecutionDescriptor
import com.jjt.ingsis.snippets.language.execution.ExecutionPayload
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.UUID

internal fun executionProblem(failure: RunFailure): ResponseEntity<ProblemDetail> {
    val status =
        when (failure) {
            RunFailure.IDENTITY_REQUIRED -> HttpStatus.UNAUTHORIZED

            RunFailure.INVALID_REQUEST, RunFailure.INVALID_CURSOR -> HttpStatus.BAD_REQUEST

            RunFailure.SNIPPET_MISSING, RunFailure.TEST_MISSING, RunFailure.EXECUTION_MISSING -> HttpStatus.NOT_FOUND

            RunFailure.ACTOR_MISMATCH -> HttpStatus.FORBIDDEN

            RunFailure.ACTIVE, RunFailure.SNAPSHOT_CONFLICT, RunFailure.HISTORY_UNAVAILABLE,
            RunFailure.CONSUMER_CONFLICT, RunFailure.INPUT_CONFLICT,
            -> HttpStatus.CONFLICT

            RunFailure.CAPACITY_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS

            RunFailure.PAYLOAD_TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE

            RunFailure.UNSUPPORTED_CAPABILITY, RunFailure.UNSUPPORTED_VERSION,
            RunFailure.INVALID_CODE,
            -> HttpStatus.UNPROCESSABLE_CONTENT

            RunFailure.DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE

            RunFailure.DEPENDENCY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT
        }
    val problem = ProblemDetail.forStatusAndDetail(status, "The execution operation could not be confirmed.")
    problem.type = URI.create("urn:snippets:problem:${failure.category}")
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
}

internal class ExecutionHttpFailure(
    val failure: RunFailure,
) : RuntimeException()

internal fun executionDescriptor(
    run: AuditedRun,
    remote: ExecutionDescriptor,
): Map<String, Any> {
    val snapshot = run.snapshot
    return listOfNotNull(
        "executionId" to run.id,
        "snippetId" to snapshot.snippetId,
        snapshot.test?.let { "testId" to it.id },
        "mode" to remote.mode,
        "state" to remote.state,
        "lastSeq" to remote.lastSeq,
        "limits" to remote.limits,
        "snapshot" to
            listOfNotNull(
                "language" to snapshot.language,
                "version" to snapshot.version,
                "codeHash" to snapshot.codeHash,
                snapshot.test?.let { "testHash" to it.hash },
            ).toMap(),
    ).toMap()
}

@Component
class PublicExecutionEventCodec(
    private val json: ObjectMapper,
) {
    fun encode(
        id: UUID,
        event: RunEvent,
    ): ByteArray {
        val (type, data) =
            when (val payload = event.payload) {
                is ExecutionPayload.Started -> {
                    "started" to mapOf("mode" to payload.mode)
                }

                is ExecutionPayload.Output -> {
                    "output" to mapOf("text" to payload.text)
                }

                is ExecutionPayload.InputRequired -> {
                    "input-required" to
                        mapOf("requestId" to payload.requestId, "prompt" to payload.prompt)
                }

                is ExecutionPayload.InputAccepted -> {
                    "input-accepted" to mapOf("requestId" to payload.requestId)
                }

                ExecutionPayload.Heartbeat -> {
                    "heartbeat" to emptyMap<String, Any>()
                }

                is ExecutionPayload.Terminal -> {
                    "terminal" to (
                        mapOf(
                            "outcome" to payload.outcome,
                            "reason" to payload.reason,
                            "diagnostics" to payload.diagnostics,
                        ) +
                            (payload.unusedInputs?.let { mapOf("unusedInputs" to it) }.orEmpty()) +
                            (event.comparison?.let { mapOf("comparison" to it) }.orEmpty())
                    )
                }
            }
        return json.writeValueAsBytes(mapOf("executionId" to id, "seq" to event.seq, "type" to type, "data" to data))
    }
}
