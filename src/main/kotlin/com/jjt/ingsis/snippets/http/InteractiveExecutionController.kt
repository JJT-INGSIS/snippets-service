package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.execution.RunFailure
import com.jjt.ingsis.snippets.execution.RunResult
import com.jjt.ingsis.snippets.execution.RunSessions
import com.jjt.ingsis.snippets.execution.StartInteractiveRun
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class InteractiveExecutionController(
    private val start: StartInteractiveRun,
    private val sessions: RunSessions,
    private val decoder: ExecutionRequestDecoder,
) {
    @PostMapping("/snippets/{snippetId}/executions", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun start(
        @PathVariable snippetId: String,
        @RequestBody body: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> {
        if (!decoder.empty(body)) return executionProblem(RunFailure.INVALID_REQUEST)
        return when (val result = start.start(snippetId, headers.actorContext())) {
            is RunResult.Refused -> executionProblem(result.failure)
            is RunResult.Success -> executionCreated(result.value)
        }
    }

    @PostMapping("/executions/{id}/inputs", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun input(
        @PathVariable id: String,
        @RequestBody body: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> {
        val input = decoder.input(body) ?: return executionProblem(RunFailure.INVALID_REQUEST)
        return when (val found = sessions.find(id, headers.actorContext())) {
            is RunResult.Refused -> executionProblem(found.failure)
            is RunResult.Success -> inputAnswer(input, found.value.input(input.requestId, input.value))
        }
    }

    private fun inputAnswer(
        input: RunInput,
        sent: RunResult<Unit>,
    ): ResponseEntity<*> =
        when (sent) {
            is RunResult.Refused -> executionProblem(sent.failure)
            is RunResult.Success -> ResponseEntity.ok(mapOf("requestId" to input.requestId, "accepted" to true))
        }
}
