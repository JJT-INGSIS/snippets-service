package com.jjt.ingsis.snippets.language.printscript

import com.jjt.ingsis.snippets.language.Diagnostic

data class PrintScriptValidationResponse(
    val valid: Boolean,
    val diagnostics: List<PrintScriptDiagnostic>,
)

data class PrintScriptDiagnostic(
    val rule: String,
    val message: String,
    val line: Int,
    val column: Int,
) {
    fun toDiagnostic(): Diagnostic =
        Diagnostic(
            rule = rule,
            message = message,
            line = line,
            column = column,
        )
}
