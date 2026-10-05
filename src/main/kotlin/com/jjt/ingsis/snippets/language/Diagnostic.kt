package com.jjt.ingsis.snippets.language

data class Diagnostic(
    val rule: String,
    val message: String,
    val line: Int,
    val column: Int,
)
