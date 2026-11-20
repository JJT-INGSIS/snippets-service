package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.storage.ContentReference

data class FormattingBasis(
    val content: ContentReference,
    val language: String,
    val version: String,
    val rules: String,
    val processor: ProcessorRevision,
) {
    fun isCurrent(current: FormattingBasis): Boolean = this == current

    companion object {
        fun of(
            snippet: LinkedSnippet,
            selection: FormattingSelection,
            processor: ProcessorRevision,
        ): FormattingBasis? =
            snippet.reference?.let { content ->
                FormattingBasis(
                    content = content,
                    language = snippet.metadata.language,
                    version = snippet.metadata.version,
                    rules = selection.revision,
                    processor = processor,
                )
            }
    }
}
