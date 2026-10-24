package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.language.SupportedLanguage

data class LanguageResponse(
    val language: String,
    val extension: String,
    val versions: List<String>,
) {
    companion object {
        fun from(language: SupportedLanguage): LanguageResponse =
            LanguageResponse(language.language, language.extension, language.versions)
    }
}
