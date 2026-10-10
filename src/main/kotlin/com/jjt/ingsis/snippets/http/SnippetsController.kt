package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.creation.CreateSnippet
import com.jjt.ingsis.snippets.creation.DraftInput
import com.jjt.ingsis.snippets.creation.DraftJsonDecoder
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/snippets")
class SnippetsController(
    private val decoder: DraftJsonDecoder,
    private val createSnippet: CreateSnippet,
    private val translator: CreationOutcomeTranslator,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        @RequestBody body: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> =
        when (val input = decoder.decode(body)) {
            DraftInput.Rejected -> {
                SnippetProblem.INVALID_REQUEST.response()
            }

            is DraftInput.Accepted -> {
                val outcome = createSnippet.create(input.draft, headers.idempotencyKey(), headers.actorContext())
                translator.toResponse(outcome)
            }
        }
}
