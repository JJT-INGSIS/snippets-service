package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.language.Language
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class LanguagesController(
    private val language: Language,
) {
    @GetMapping("/languages", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun languages(): List<LanguageResponse> = language.supportedLanguages().map(LanguageResponse::from)
}
