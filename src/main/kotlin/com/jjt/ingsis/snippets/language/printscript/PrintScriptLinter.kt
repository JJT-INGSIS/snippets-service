package com.jjt.ingsis.snippets.language.printscript

import com.jjt.ingsis.snippets.language.Attributed
import com.jjt.ingsis.snippets.language.linting.LanguageLinter
import com.jjt.ingsis.snippets.language.linting.LintCatalogResult
import com.jjt.ingsis.snippets.language.linting.LintCheckResult
import com.jjt.ingsis.snippets.language.linting.LintResult
import com.jjt.ingsis.snippets.language.linting.LintRule
import com.jjt.ingsis.snippets.language.linting.LintViolation
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

data class PrintScriptLintCatalog(
    val version: String,
    val rules: List<LintRule>,
    val defaults: Map<String, Any?>? = null,
)

data class PrintScriptLintCheck(
    val valid: Boolean,
    val violations: List<LintViolation>,
)

data class PrintScriptLintResponse(
    val analyzed: Boolean,
    val compliant: Boolean?,
    val diagnostics: List<PrintScriptDiagnostic>,
)

data class PrintScriptLintRequest(
    val version: String,
    val code: String,
    val rules: Map<String, Any?>,
)

@Component
class PrintScriptLinter(
    properties: PrintScriptProperties,
) : LanguageLinter {
    private val exchange = PrintScriptExchange(properties)
    override val language: String = "printscript"

    override fun catalog(version: String): Attributed<LintCatalogResult> {
        val response = exchange.get("/linting/rules?version={version}", version, PrintScriptLintCatalog::class.java)
        val result =
            when (val answer = response.result) {
                is PrintScriptAnswer.Answered -> {
                    answer.body?.takeIf { it.version == version }?.let {
                        LintCatalogResult.Catalog(it.rules, it.defaults?.toMap())
                    } ?: LintCatalogResult.Failed(UNAVAILABLE)
                }

                is PrintScriptAnswer.Rejected -> {
                    if (answer.isUnsupportedVersion) {
                        LintCatalogResult.UnsupportedVersion(language, version)
                    } else {
                        LintCatalogResult.Failed(UNAVAILABLE)
                    }
                }

                PrintScriptAnswer.Unavailable -> {
                    LintCatalogResult.Failed(UNAVAILABLE)
                }
            }
        return Attributed(result, response.processor)
    }

    override fun check(
        version: String,
        rules: Map<String, Any?>,
    ): Attributed<LintCheckResult> {
        val response =
            exchange.post(
                "/linting/rules/check",
                PrintScriptRulesCheckRequest(version, rules),
                PrintScriptLintCheck::class.java,
            )
        val result =
            when (val answer = response.result) {
                is PrintScriptAnswer.Answered -> {
                    answer.body?.takeIf { it.valid == it.violations.isEmpty() }?.let {
                        LintCheckResult.Checked(it.valid, it.violations)
                    } ?: LintCheckResult.Failed(UNAVAILABLE)
                }

                is PrintScriptAnswer.Rejected -> {
                    if (answer.isUnsupportedVersion) {
                        LintCheckResult.UnsupportedVersion(language, version)
                    } else {
                        LintCheckResult.Failed(UNAVAILABLE)
                    }
                }

                PrintScriptAnswer.Unavailable -> {
                    LintCheckResult.Failed(UNAVAILABLE)
                }
            }
        return Attributed(result, response.processor)
    }

    override fun lint(
        version: String,
        code: String,
        rules: Map<String, Any?>,
    ): Attributed<LintResult> {
        val response =
            exchange.post(
                "/linting",
                PrintScriptLintRequest(version, code, rules),
                PrintScriptLintResponse::class.java,
            )
        val result =
            when (val answer = response.result) {
                is PrintScriptAnswer.Answered -> resultOf(answer.body)
                is PrintScriptAnswer.Rejected -> rejected(answer, version)
                PrintScriptAnswer.Unavailable -> LintResult.Failed(UNAVAILABLE)
            }
        return Attributed(result, response.processor)
    }

    private fun resultOf(body: PrintScriptLintResponse?): LintResult =
        when {
            body == null -> {
                LintResult.Failed(UNAVAILABLE)
            }

            body.analyzed && body.compliant == body.diagnostics.isEmpty() -> {
                LintResult.Analyzed(body.diagnostics.map { it.toDiagnostic() })
            }

            !body.analyzed && body.compliant == null && body.diagnostics.isNotEmpty() -> {
                LintResult.InvalidCode(body.diagnostics.map { it.toDiagnostic() })
            }

            else -> {
                LintResult.Failed(UNAVAILABLE)
            }
        }

    private fun rejected(
        answer: PrintScriptAnswer.Rejected,
        version: String,
    ): LintResult =
        when {
            answer.isUnsupportedVersion -> {
                LintResult.UnsupportedVersion(language, version)
            }

            answer.status == HttpStatus.UNPROCESSABLE_CONTENT.value() &&
                answer.problem.type == "urn:printscript:problem:invalid-linting-rules" &&
                !answer.problem.violations.isNullOrEmpty() -> {
                LintResult.InvalidRules(answer.problem.violations.map { LintViolation(it.rule, it.reason) })
            }

            answer.status == HttpStatus.BAD_REQUEST.value() &&
                answer.problem.type == "urn:printscript:problem:request-too-large" -> {
                LintResult.CodeTooLarge
            }

            answer.status == HttpStatus.UNPROCESSABLE_CONTENT.value() &&
                answer.problem.type == "urn:printscript:problem:lint-diagnostics-too-large" -> {
                LintResult.DiagnosticsTooLarge
            }

            else -> {
                LintResult.Failed(UNAVAILABLE)
            }
        }
}
