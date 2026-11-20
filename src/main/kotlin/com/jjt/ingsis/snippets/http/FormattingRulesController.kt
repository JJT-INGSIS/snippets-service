package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.formattingrules.ReadFormattingRules
import com.jjt.ingsis.snippets.formattingrules.SaveFormattingRules
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/formatting-rules/{language}/{version}")
class FormattingRulesController(
    private val decoder: FormattingRulesRequestDecoder,
    private val readRules: ReadFormattingRules,
    private val saveRules: SaveFormattingRules,
    private val translator: FormattingRulesOutcomeTranslator,
) {
    @GetMapping
    fun read(
        @PathVariable language: String,
        @PathVariable version: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> = translator.toResponse(readRules.read(language, version, headers.actorContext()))

    @PutMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun save(
        @PathVariable language: String,
        @PathVariable version: String,
        @RequestBody body: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> {
        val rules = decoder.decode(body) ?: return SnippetProblem.INVALID_REQUEST.response()

        return translator.toResponse(
            saveRules.save(language = language, version = version, rules = rules, context = headers.actorContext()),
        )
    }
}
