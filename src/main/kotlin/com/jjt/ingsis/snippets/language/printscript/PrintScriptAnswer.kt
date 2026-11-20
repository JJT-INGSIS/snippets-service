package com.jjt.ingsis.snippets.language.printscript

import org.springframework.http.HttpStatus

sealed interface PrintScriptAnswer<out T> {
    data class Answered<T>(
        val body: T?,
    ) : PrintScriptAnswer<T>

    data class Rejected(
        val status: Int,
        val problem: PrintScriptProblem,
    ) : PrintScriptAnswer<Nothing> {
        val isUnsupportedVersion: Boolean
            get() = status == HttpStatus.UNPROCESSABLE_CONTENT.value() && problem.supportedVersions != null
    }

    data object Unavailable : PrintScriptAnswer<Nothing>
}
