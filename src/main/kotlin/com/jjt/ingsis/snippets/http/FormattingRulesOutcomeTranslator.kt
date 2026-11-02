package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.formattingrules.FormattingRulesOutcome
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

@Component
class FormattingRulesOutcomeTranslator {
    fun toResponse(outcome: FormattingRulesOutcome): ResponseEntity<*> =
        when (outcome) {
            is FormattingRulesOutcome.Shown -> ResponseEntity.ok(FormattingRulesResponse.from(outcome.view))
            is FormattingRulesOutcome.InvalidRules -> invalid(outcome)
            is FormattingRulesOutcome.UnsupportedLanguage -> SnippetProblem.UNSUPPORTED_LANGUAGE.response()
            is FormattingRulesOutcome.UnsupportedVersion -> SnippetProblem.UNSUPPORTED_VERSION.response()
            FormattingRulesOutcome.LanguageUnavailable -> SnippetProblem.DEPENDENCY_FAILURE.response()
            FormattingRulesOutcome.IdentityRejected -> SnippetProblem.IDENTITY_UNAVAILABLE.response()
            FormattingRulesOutcome.MetadataUnavailable -> SnippetProblem.METADATA_UNAVAILABLE.response()
        }

    private fun invalid(outcome: FormattingRulesOutcome.InvalidRules): ResponseEntity<*> =
        SnippetProblem.INVALID_FORMATTING_RULES.response("violations", outcome.violations)
}
