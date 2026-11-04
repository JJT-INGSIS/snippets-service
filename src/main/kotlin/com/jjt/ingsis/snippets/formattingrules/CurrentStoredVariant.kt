package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import org.springframework.stereotype.Component

data class StoredVariant(
    val snippet: SnippetMetadata,
    val code: String,
    val basis: FormattingBasis,
)

@Component
class CurrentStoredVariant(
    private val variants: FormattedVariants,
    private val storage: SnippetContentStorage,
    private val links: SnippetContentLinks,
    private val rules: ApplicableFormattingRules,
    private val language: Language,
) {
    fun read(
        snippet: LinkedSnippet,
        ownerId: String,
    ): StoredVariant? {
        val variant = variants.find(snippet.metadata.id) ?: return null
        val processor = language.formatProcessor(snippet.metadata.language).verifiedOrNull() ?: return null
        if (!isCurrent(variant = variant, snippet = snippet, ownerId = ownerId, processor = processor)) return null
        val code = (storage.read(variant.derived) as? ReadResult.Found)?.code ?: return null
        val now = links.findLinked(snippet.metadata.id)?.takeIf { it.metadata.state == SnippetState.CONFIRMED }

        return if (now != null &&
            isCurrent(variant = variant, snippet = now, ownerId = ownerId, processor = processor)
        ) {
            StoredVariant(now.metadata, code, variant.basis)
        } else {
            null
        }
    }

    private fun isCurrent(
        variant: FormattedVariant,
        snippet: LinkedSnippet,
        ownerId: String,
        processor: ProcessorRevision,
    ): Boolean {
        val current = FormattingBasis.of(snippet, rules.of(snippet.metadata, ownerId), processor)

        return current != null && variant.basis.isCurrent(current)
    }
}
