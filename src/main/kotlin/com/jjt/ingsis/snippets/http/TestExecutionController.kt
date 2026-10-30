package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.execution.RunFailure
import com.jjt.ingsis.snippets.execution.RunResult
import com.jjt.ingsis.snippets.execution.RunSessions
import com.jjt.ingsis.snippets.execution.StartTestRun
import com.jjt.ingsis.snippets.execution.executionFailure
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody

@RestController
class TestExecutionController(
    private val start: StartTestRun,
    private val sessions: RunSessions,
    private val decoder: ExecutionRequestDecoder,
    private val codec: PublicExecutionEventCodec,
) {
    @PostMapping("/snippets/{snippetId}/tests/{testId}/executions", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun start(
        @PathVariable snippetId: String,
        @PathVariable testId: String,
        @RequestBody body: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> {
        if (!decoder.empty(body)) return executionProblem(RunFailure.INVALID_REQUEST)
        return when (val result = start.start(snippetId, testId, headers.actorContext())) {
            is RunResult.Refused -> {
                executionProblem(result.failure)
            }

            is RunResult.Success -> {
                executionCreated(result.value)
            }
        }
    }

    @GetMapping("/executions/{id}")
    fun describe(
        @PathVariable id: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> =
        when (val found = sessions.find(id, headers.actorContext())) {
            is RunResult.Refused -> {
                executionProblem(found.failure)
            }

            is RunResult.Success -> {
                when (val described = sessions.describe(found.value)) {
                    is RunResult.Refused -> executionProblem(described.failure)
                    is RunResult.Success -> ResponseEntity.ok(executionDescriptor(found.value.run, described.value))
                }
            }
        }

    @PostMapping("/executions/{id}/cancel", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun cancel(
        @PathVariable id: String,
        @RequestBody body: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> {
        if (!decoder.empty(body)) return executionProblem(RunFailure.INVALID_REQUEST)
        return when (val found = sessions.find(id, headers.actorContext())) {
            is RunResult.Refused -> {
                executionProblem(found.failure)
            }

            is RunResult.Success -> {
                when (val cancelled = found.value.cancel()) {
                    is ExecutionResult.Success -> ResponseEntity.accepted().body(mapOf("state" to cancelled.value))
                    is ExecutionResult.Rejected -> executionProblem(executionFailure(cancelled.reason))
                    is ExecutionResult.Failed -> executionProblem(RunFailure.DEPENDENCY_UNAVAILABLE)
                }
            }
        }
    }

    @GetMapping("/executions/{id}/events", produces = ["application/x-ndjson"])
    fun events(
        @PathVariable id: String,
        @RequestParam(defaultValue = "0") after: String,
        @RequestHeader headers: HttpHeaders,
        response: HttpServletResponse,
    ): ResponseEntity<StreamingResponseBody> {
        val cursor = after.toLongOrNull() ?: throw ExecutionHttpFailure(RunFailure.INVALID_CURSOR)
        return when (val found = sessions.find(id, headers.actorContext())) {
            is RunResult.Refused -> {
                throw ExecutionHttpFailure(found.failure)
            }

            is RunResult.Success -> {
                when (val opened = sessions.open(found.value, cursor)) {
                    is RunResult.Refused -> {
                        throw ExecutionHttpFailure(opened.failure)
                    }

                    is RunResult.Success -> {
                        val stream = opened.value
                        val body =
                            StreamingResponseBody { output ->
                                stream.use {
                                    it.read { event ->
                                        output.write(codec.encode(found.value.run.id, event))
                                        output.write('\n'.code)
                                        response.flushBuffer()
                                    }
                                }
                            }
                        ResponseEntity
                            .ok()
                            .contentType(MediaType.parseMediaType("application/x-ndjson"))
                            .header("Cache-Control", "no-store")
                            .header("X-Accel-Buffering", "no")
                            .body(body)
                    }
                }
            }
        }
    }
}
