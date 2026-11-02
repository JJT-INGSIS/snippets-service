package com.jjt.ingsis.snippets.language.printscript

import com.jjt.ingsis.snippets.language.formatting.FormattingRule
import com.jjt.ingsis.snippets.language.formatting.FormattingRuleViolation

data class PrintScriptFormattingRequest(
    val code: String,
    val version: String,
    val rules: Map<String, Any?>,
)

data class PrintScriptFormattingCatalog(
    val version: String,
    val rules: List<FormattingRule>,
)

data class PrintScriptFormattingResponse(
    val formatted: Boolean,
    val code: String?,
    val diagnostics: List<PrintScriptDiagnostic>,
)

data class PrintScriptProblem(
    val type: String? = null,
    val violations: List<FormattingRuleViolation>? = null,
    val supportedVersions: List<String>? = null,
)
