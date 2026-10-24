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
        "The body, one of its fields, a header or the snippet id is missing or invalid.",
    ),
    IDENTITY_UNAVAILABLE(
        HttpStatus.UNAUTHORIZED,
        "identity-unavailable",
        "The request does not carry a usable identity.",
    ),
    MODIFICATION_DENIED(
        HttpStatus.FORBIDDEN,
        "modification-denied",
        "Only the owner can modify this snippet.",
    ),
    SNIPPET_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "snippet-not-found",
        "There is no snippet with that id.",
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
    SNIPPET_PENDING(
        HttpStatus.CONFLICT,
        "snippet-pending",
        "The creation of this snippet is not confirmed yet.",
    ),
    OWNERSHIP_MISSING(
        HttpStatus.CONFLICT,
        "ownership-missing",
        "The snippet has no registered owner, so nobody can modify it.",
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
        "A service this operation depends on did not answer correctly. Repeating the same request is safe.",
    ),
    METADATA_UNAVAILABLE(
        HttpStatus.SERVICE_UNAVAILABLE,
        "metadata-unavailable",
        "The snippet data is not available right now. Repeating the same request is safe.",
    ),
    STORAGE_UNAVAILABLE(
        HttpStatus.SERVICE_UNAVAILABLE,
        "storage-unavailable",
        "The snippet code could not be saved. Repeating the same request is safe.",
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
