package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.lintingrules.LintingRulesOutcome
import com.jjt.ingsis.snippets.lintingrules.ReadLintingRules
import com.jjt.ingsis.snippets.lintingrules.SaveLintingRules
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class LintingRulesController(
    private val read: ReadLintingRules,
    private val save: SaveLintingRules,
    private val decoder: LintingRulesRequestDecoder,
) {
    @GetMapping("/linting-rules/{language}/{version}")
    fun get(
        @PathVariable language: String,
        @PathVariable version: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<*> = response(read.read(language, version, headers.actorContext()))

    @PutMapping("/linting-rules/{language}/{version}", consumes = ["application/json"])
    fun put(
        @PathVariable language: String,
        @PathVariable version: String,
        @RequestHeader headers: HttpHeaders,
        @RequestBody body: String,
    ): ResponseEntity<*> {
        val rules = decoder.decode(body) ?: return SnippetProblem.INVALID_REQUEST.response()
        return response(
            save.save(language = language, version = version, rules = rules, context = headers.actorContext()),
        )
    }

    private fun response(outcome: LintingRulesOutcome): ResponseEntity<*> =
        when (outcome) {
            is LintingRulesOutcome.Shown -> {
                ResponseEntity.ok(LintingRulesResponse.from(outcome.view))
            }

            is LintingRulesOutcome.InvalidRules -> {
                SnippetProblem.INVALID_LINTING_RULES.response(
                    "violations",
                    outcome.violations,
                )
            }

            is LintingRulesOutcome.UnsupportedLanguage -> {
                SnippetProblem.UNSUPPORTED_LANGUAGE.response()
            }

            is LintingRulesOutcome.UnsupportedVersion -> {
                SnippetProblem.UNSUPPORTED_VERSION.response()
            }

            LintingRulesOutcome.LanguageUnavailable -> {
                SnippetProblem.DEPENDENCY_FAILURE.response()
            }

            LintingRulesOutcome.IdentityRejected -> {
                SnippetProblem.IDENTITY_UNAVAILABLE.response()
            }

            LintingRulesOutcome.MetadataUnavailable -> {
                SnippetProblem.METADATA_UNAVAILABLE.response()
            }
        }
}
