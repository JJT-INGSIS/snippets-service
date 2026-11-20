package com.jjt.ingsis.snippets.testcase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private fun definition(
    inputs: List<String> = emptyList(),
    expectedOutputs: List<String> = emptyList(),
): TestCaseDefinition = TestCaseDefinition(inputs, expectedOutputs)

class TestCaseRulesTest {
    @Test
    fun `empty sequences empty strings duplicates and unicode are accepted`() {
        assertNull(TestCaseRules.rejectionOf(definition()))
        assertNull(TestCaseRules.rejectionOf(definition(listOf("", "", "ñ"), listOf("🌎", "e\u0301", ""))))
    }

    @Test
    fun `the item count limit applies to each sequence`() {
        val full = List(TestCaseRules.MAXIMUM_ITEMS) { "x" }

        assertNull(TestCaseRules.rejectionOf(definition(full, full)))
        assertEquals(DefinitionRejection.TOO_LARGE, TestCaseRules.rejectionOf(definition(inputs = full + "x")))
        assertEquals(DefinitionRejection.TOO_LARGE, TestCaseRules.rejectionOf(definition(expectedOutputs = full + "x")))
    }

    @Test
    fun `item size is measured in UTF-8 bytes not characters`() {
        val twoBytes = "ñ".repeat(TestCaseRules.MAXIMUM_ITEM_BYTES / 2)

        assertNull(TestCaseRules.rejectionOf(definition(inputs = listOf(twoBytes))))
        assertEquals(
            DefinitionRejection.TOO_LARGE,
            TestCaseRules.rejectionOf(definition(expectedOutputs = listOf(twoBytes + "a"))),
        )
    }

    @Test
    fun `text the database cannot store exactly is malformed`() {
        assertEquals(DefinitionRejection.MALFORMED, TestCaseRules.rejectionOf(definition(inputs = listOf("a\u0000b"))))
        assertEquals(
            DefinitionRejection.MALFORMED,
            TestCaseRules.rejectionOf(definition(expectedOutputs = listOf("\uD800"))),
        )
    }
}
