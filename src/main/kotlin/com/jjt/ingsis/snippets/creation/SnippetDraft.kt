package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.metadata.SnippetDetails
import java.util.UUID

data class SnippetDraft(
    val name: String,
    val description: String?,
    val language: String,
    val version: String,
    val code: String,
) {
    fun invalidField(): String? =
        listOf("name" to name, "language" to language, "version" to version)
            .firstOrNull { (_, value) -> value.isBlank() }
            ?.first

    fun details(): SnippetDetails =
        SnippetDetails(name = name, description = description, language = language, version = version)
}

internal fun canonicalUuid(value: String?): UUID? =
    try {
        value?.let { UUID.fromString(it) }?.takeIf { it.toString().equals(value, ignoreCase = true) }
    } catch (_: IllegalArgumentException) {
        null
    }
