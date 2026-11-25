package com.jjt.ingsis.snippets.listing

import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.lintquery.LintFinding
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.permissions.SnippetAccess
import org.springframework.stereotype.Component

@Component
class ListedSnippetAssembler(
    private val language: Language,
) {
    fun assemble(
        metadata: SnippetMetadata,
        access: SnippetAccess,
        lint: LintFinding,
    ): ListedSnippet =
        ListedSnippet(
            metadata = metadata,
            access = access,
            extension = language.extensionOf(metadata.language),
            lint = lint,
        )
}
