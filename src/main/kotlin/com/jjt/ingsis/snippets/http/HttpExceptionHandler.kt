package com.jjt.ingsis.snippets.http

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@RestControllerAdvice
class HttpExceptionHandler : ResponseEntityExceptionHandler() {
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problem = if (statusCode == HttpStatus.BAD_REQUEST) SnippetProblem.INVALID_REQUEST.body() else body

        return super.handleExceptionInternal(ex, problem, headers, statusCode, request)
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(failure: Exception): ResponseEntity<ProblemDetail> {
        logger.error("The request failed unexpectedly", failure)

        return SnippetProblem.TECHNICAL_FAILURE.response()
    }
}
