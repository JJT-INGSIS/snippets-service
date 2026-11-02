package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.storage.ContentReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class FormattingBasisTest {
    private val processor = ProcessorRevision("sha256-v1:" + "a".repeat(64))

    private val metadata =
        SnippetMetadata(
            id = UUID.randomUUID(),
            name = "Example",
            description = null,
            language = "printscript",
            version = "1.1",
            state = SnippetState.CONFIRMED,
        )

    private val selection = FormattingSelection(mapOf("indent-inside-if" to 2))

    private val basis = FormattingBasis.of(LinkedSnippet(metadata, ContentReference("first")), selection, processor)!!

    @Test
    fun `a basis is current only while code language version rules and processor stay the same`() {
        val otherProcessor = ProcessorRevision("sha256-v1:" + "b".repeat(64))
        val otherRules = FormattingSelection(mapOf("indent-inside-if" to 4)).revision

        assertThat(basis.isCurrent(basis.copy())).isTrue()
        assertThat(basis.isCurrent(basis.copy(content = ContentReference("second")))).isFalse()
        assertThat(basis.isCurrent(basis.copy(language = "other"))).isFalse()
        assertThat(basis.isCurrent(basis.copy(version = "1.0"))).isFalse()
        assertThat(basis.isCurrent(basis.copy(rules = otherRules))).isFalse()
        assertThat(basis.isCurrent(basis.copy(processor = otherProcessor))).isFalse()
    }

    @Test
    fun `an edit stores a new content reference so the previous basis stops being current`() {
        val edited = FormattingBasis.of(LinkedSnippet(metadata, ContentReference("second")), selection, processor)!!

        assertThat(basis.isCurrent(edited)).isFalse()
    }

    @Test
    fun `a snippet without content has no basis`() {
        assertThat(FormattingBasis.of(LinkedSnippet(metadata, null), selection, processor)).isNull()
    }
}
