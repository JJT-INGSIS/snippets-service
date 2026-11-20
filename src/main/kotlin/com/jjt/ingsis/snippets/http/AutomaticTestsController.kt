package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.access.ReadFailure
import com.jjt.ingsis.snippets.automatic.AutomaticLookup
import com.jjt.ingsis.snippets.automatic.CurrentAutomaticTests
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class AutomaticTestsController(
    private val current: CurrentAutomaticTests,
) {
    @GetMapping("/snippets/{snippetId}/automatic-tests")
    fun find(
        @PathVariable snippetId: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> =
        when (val result = current.find(snippetId, headers.actorContext())) {
            is AutomaticLookup.Found -> {
                ResponseEntity
                    .ok()
                    .cacheControl(
                        org.springframework.http.CacheControl
                            .noStore(),
                    ).body(result.view)
            }

            is AutomaticLookup.Refused -> {
                when (result.failure) {
                    ReadFailure.IDENTITY_REJECTED -> SnippetProblem.IDENTITY_UNAVAILABLE.response()
                    ReadFailure.INVALID_ID -> SnippetProblem.INVALID_REQUEST.response("field", "snippetId")
                    ReadFailure.SNIPPET_MISSING -> SnippetProblem.SNIPPET_NOT_FOUND.response()
                    ReadFailure.PERMISSIONS_UNAVAILABLE -> SnippetProblem.DEPENDENCY_FAILURE.response()
                    ReadFailure.METADATA_UNAVAILABLE -> SnippetProblem.METADATA_UNAVAILABLE.response()
                }
            }

            AutomaticLookup.Unavailable -> {
                SnippetProblem.METADATA_UNAVAILABLE.response()
            }
        }
}
