package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.access.ReadFailure
import com.jjt.ingsis.snippets.testcase.DefinitionRejection
import com.jjt.ingsis.snippets.testcase.TestCaseAddition
import com.jjt.ingsis.snippets.testcase.TestCaseListing
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

private const val LIMIT = "limit"

@Component
class TestCaseOutcomeTranslator {
    fun toResponse(addition: TestCaseAddition): ResponseEntity<*> =
        when (addition) {
            is TestCaseAddition.Created -> {
                ResponseEntity.status(HttpStatus.CREATED).body(TestCaseResponse.from(addition.testCase))
            }

            is TestCaseAddition.Invalid -> {
                rejected(addition.rejection)
            }

            TestCaseAddition.LimitReached -> {
                SnippetProblem.PAYLOAD_TOO_LARGE.response(LIMIT, "testsPerSnippet")
            }

            TestCaseAddition.NotOwner -> {
                SnippetProblem.ACCESS_DENIED.response()
            }

            is TestCaseAddition.Failed -> {
                failed(addition.failure)
            }
        }

    fun toResponse(listing: TestCaseListing): ResponseEntity<*> =
        when (listing) {
            is TestCaseListing.Listed -> {
                ResponseEntity.ok(
                    TestCaseListResponse(listing.testCases.map(TestCaseResponse::from)),
                )
            }

            is TestCaseListing.Failed -> {
                failed(listing.failure)
            }
        }

    private fun rejected(rejection: DefinitionRejection): ResponseEntity<*> =
        when (rejection) {
            DefinitionRejection.MALFORMED -> SnippetProblem.INVALID_REQUEST.response()
            DefinitionRejection.TOO_LARGE -> SnippetProblem.PAYLOAD_TOO_LARGE.response(LIMIT, "definition")
        }

    private fun failed(failure: ReadFailure): ResponseEntity<*> =
        when (failure) {
            ReadFailure.IDENTITY_REJECTED -> SnippetProblem.IDENTITY_UNAVAILABLE.response()
            ReadFailure.INVALID_ID -> SnippetProblem.INVALID_REQUEST.response("field", "snippetId")
            ReadFailure.SNIPPET_MISSING -> SnippetProblem.SNIPPET_NOT_FOUND.response()
            ReadFailure.PERMISSIONS_UNAVAILABLE -> SnippetProblem.DEPENDENCY_FAILURE.response()
            ReadFailure.METADATA_UNAVAILABLE -> SnippetProblem.METADATA_UNAVAILABLE.response()
        }
}
