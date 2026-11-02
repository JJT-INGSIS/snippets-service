package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import org.springframework.stereotype.Component

@Component
class ApplicableFormattingRules(
    private val store: FormattingRulesStore,
) {
    fun of(
        snippet: SnippetMetadata,
        ownerId: String,
    ): FormattingSelection =
        store.find(FormattingScope(ownerId, snippet.language, snippet.version))?.selection
            ?: FormattingSelection.EMPTY
}
