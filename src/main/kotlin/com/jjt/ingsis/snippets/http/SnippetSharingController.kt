package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.sharing.ShareSnippet
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/snippets")
class SnippetSharingController(
    private val shareSnippet: ShareSnippet,
    private val translator: ShareOutcomeTranslator,
) {
    @PutMapping("/{snippetId}/shares/{userId}")
    fun share(
        @PathVariable snippetId: String,
        @PathVariable userId: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> = translator.toResponse(shareSnippet.share(snippetId, userId, headers.actorContext()))
}
