package com.jjt.ingsis.snippets.language

data class SupportedLanguage(
    val language: String,
    val extension: String,
    val versions: List<String>,
)
