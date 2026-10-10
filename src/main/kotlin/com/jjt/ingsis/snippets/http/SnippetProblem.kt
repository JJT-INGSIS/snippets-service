package com.jjt.ingsis.snippets.http

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import java.net.URI

enum class SnippetProblem(
    val status: HttpStatus,
    val category: String,
    private val detail: String,
) {
    INVALID_REQUEST(
        HttpStatus.BAD_REQUEST,
        "invalid-request",
        "The body, one of its fields or the Idempotency-Key header is missing or invalid.",
    ),
    IDENTITY_UNAVAILABLE(
        HttpStatus.UNAUTHORIZED,
        "identity-unavailable",
        "The request does not carry a usable identity.",
    ),
    CREATION_CONFLICT(
        HttpStatus.CONFLICT,
        "creation-conflict",
        "The Idempotency-Key was already used by another request.",
    ),
    OWNERSHIP_CONFLICT(
        HttpStatus.CONFLICT,
        "ownership-conflict",
        "The snippet already has a different owner. The creation is not confirmed.",
    ),
    INVALID_CODE(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "invalid-code",
        "The code is not valid for the requested language and version.",
    ),
    UNSUPPORTED_LANGUAGE(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "unsupported-language",
        "The language is not supported.",
    ),
    UNSUPPORTED_VERSION(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "unsupported-version",
        "The language does not support that version.",
    ),
    TECHNICAL_FAILURE(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "technical-failure",
        "The operation could not complete.",
    ),
    DEPENDENCY_FAILURE(
        HttpStatus.BAD_GATEWAY,
        "dependency-failure",
        "A service this operation depends on did not answer correctly. Retry with the same Idempotency-Key.",
    ),
    METADATA_UNAVAILABLE(
        HttpStatus.SERVICE_UNAVAILABLE,
        "metadata-unavailable",
        "The snippet data could not be saved. Retry with the same Idempotency-Key.",
    ),
    STORAGE_UNAVAILABLE(
        HttpStatus.SERVICE_UNAVAILABLE,
        "storage-unavailable",
        "The snippet code could not be saved. Retry with the same Idempotency-Key.",
    ),
    ;

    fun body(): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(status, detail)
        problem.type = URI.create("urn:snippets:problem:$category")

        return problem
    }

    fun response(): ResponseEntity<ProblemDetail> = ResponseEntity.status(status).body(body())

    fun response(
        property: String,
        value: Any,
    ): ResponseEntity<ProblemDetail> {
        val problem = body()
        problem.setProperty(property, value)

        return ResponseEntity.status(status).body(problem)
    }
}
