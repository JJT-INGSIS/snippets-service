package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.testcase.CreateTestCase
import com.jjt.ingsis.snippets.testcase.ListTestCases
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/snippets/{snippetId}/tests")
class TestCasesController(
    private val decoder: TestCaseRequestDecoder,
    private val createTestCase: CreateTestCase,
    private val listTestCases: ListTestCases,
    private val translator: TestCaseOutcomeTranslator,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        @PathVariable snippetId: String,
        @RequestBody body: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> {
        val definition = decoder.decode(body) ?: return SnippetProblem.INVALID_REQUEST.response()

        return translator.toResponse(createTestCase.create(snippetId, definition, headers.actorContext()))
    }

    @GetMapping
    fun list(
        @PathVariable snippetId: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> = translator.toResponse(listTestCases.list(snippetId, headers.actorContext()))
}
