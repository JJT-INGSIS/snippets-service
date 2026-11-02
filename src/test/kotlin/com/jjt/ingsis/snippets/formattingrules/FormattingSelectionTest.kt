package com.jjt.ingsis.snippets.formattingrules

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FormattingSelectionTest {
    @Test
    fun `the revision depends on the rules and not on their order`() {
        val first = FormattingSelection(linkedMapOf("indent-inside-if" to 2, "enforce-spacing-around-equals" to true))
        val second = FormattingSelection(linkedMapOf("enforce-spacing-around-equals" to true, "indent-inside-if" to 2))

        assertThat(first.revision).isEqualTo(second.revision)
        assertThat(first.revision).matches("sha256-v1:[0-9a-f]{64}")
    }

    @Test
    fun `a different value changes the revision but the number type does not`() {
        assertThat(FormattingSelection(mapOf("indent-inside-if" to 2)).revision)
            .isNotEqualTo(FormattingSelection(mapOf("indent-inside-if" to 4)).revision)
        assertThat(FormattingSelection(mapOf("indent-inside-if" to 2)).revision)
            .isEqualTo(FormattingSelection(mapOf("indent-inside-if" to 2L)).revision)
    }

    @Test
    fun `no rules is the empty selection`() {
        assertThat(FormattingSelection(emptyMap())).isEqualTo(FormattingSelection.EMPTY)
        assertThat(FormattingSelection(emptyMap()).revision).isEqualTo(FormattingSelection.EMPTY.revision)
        assertThat(FormattingSelection.EMPTY.revision)
            .isNotEqualTo(FormattingSelection(mapOf("indent-inside-if" to 0)).revision)
    }
}
