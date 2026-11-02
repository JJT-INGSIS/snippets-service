package com.jjt.ingsis.snippets.language.printscript

import com.jjt.ingsis.snippets.language.Attributed
import com.jjt.ingsis.snippets.language.formatting.FormattingCatalogResult
import com.jjt.ingsis.snippets.language.formatting.FormattingCheckResult
import com.jjt.ingsis.snippets.language.formatting.FormattingResult
import com.jjt.ingsis.snippets.language.formatting.LanguageFormatter
import org.springframework.stereotype.Component

private const val INVALID_RULES = "urn:printscript:problem:invalid-formatting-rules"
private const val OUTPUT_TOO_LARGE = "urn:printscript:problem:formatted-output-too-large"
private const val CODE_TOO_LARGE = "urn:printscript:problem:request-too-large"
internal const val UNAVAILABLE = "The PrintScript service is unavailable or did not answer as its contract says"

@Component
class PrintScriptFormatter(
    properties: PrintScriptProperties,
) : LanguageFormatter {
    private val printScript = PrintScriptExchange(properties)

    override val language: String = "printscript"

    override fun catalog(version: String): Attributed<FormattingCatalogResult> {
        val exchange =
            printScript.get("/formatting/rules?version={version}", version, PrintScriptFormattingCatalog::class.java)
        return Attributed(PrintScriptRuleAnswers.catalogOf(exchange.result, language, version), exchange.processor)
    }

    override fun check(
        version: String,
        rules: Map<String, Any?>,
    ): Attributed<FormattingCheckResult> {
        val request = PrintScriptRulesCheckRequest(version = version, rules = rules)
        val exchange = printScript.post("/formatting/rules/check", request, PrintScriptRulesCheck::class.java)
        val result =
            PrintScriptRuleAnswers.checkOf(
                answer = exchange.result,
                language = language,
                version = version,
                rules = rules,
            )
        return Attributed(result, exchange.processor)
    }

    override fun format(
        version: String,
        code: String,
        rules: Map<String, Any?>,
    ): Attributed<FormattingResult> {
        val request = PrintScriptFormattingRequest(code = code, version = version, rules = rules)
        val exchange = printScript.post("/formatting", request, PrintScriptFormattingResponse::class.java)
        return Attributed(formattingOf(exchange.result, version), exchange.processor)
    }

    private fun formattingOf(
        answer: PrintScriptAnswer<PrintScriptFormattingResponse>,
        version: String,
    ): FormattingResult =
        when (answer) {
            is PrintScriptAnswer.Answered -> formattedFrom(answer.body)
            is PrintScriptAnswer.Rejected -> formattingRejection(answer, version)
            PrintScriptAnswer.Unavailable -> FormattingResult.Failed(UNAVAILABLE)
        }

    private fun formattedFrom(body: PrintScriptFormattingResponse?): FormattingResult =
        when {
            body == null -> FormattingResult.Failed(UNAVAILABLE)
            !body.formatted -> FormattingResult.Unformattable(body.diagnostics.map { it.toDiagnostic() })
            body.code == null -> FormattingResult.Failed(UNAVAILABLE)
            else -> FormattingResult.Formatted(body.code)
        }

    private fun formattingRejection(
        rejected: PrintScriptAnswer.Rejected,
        version: String,
    ): FormattingResult =
        when (rejected.problem.type) {
            INVALID_RULES -> FormattingResult.InvalidRules(rejected.problem.violations.orEmpty())
            OUTPUT_TOO_LARGE -> FormattingResult.OutputTooLarge
            CODE_TOO_LARGE -> FormattingResult.CodeTooLarge
            else -> versionRejection(rejected, version)
        }

    private fun versionRejection(
        rejected: PrintScriptAnswer.Rejected,
        version: String,
    ): FormattingResult =
        if (rejected.isUnsupportedVersion) {
            FormattingResult.UnsupportedVersion(language, version)
        } else {
            FormattingResult.Failed(UNAVAILABLE)
        }
}
