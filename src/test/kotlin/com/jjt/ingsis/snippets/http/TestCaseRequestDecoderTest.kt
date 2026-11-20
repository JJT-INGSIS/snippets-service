package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.testcase.TestCaseDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TestCaseRequestDecoderTest {
    private val decoder = TestCaseRequestDecoder()

    @Test
    fun `the exact sequences are kept with order duplicates empty strings and escapes`() {
        val json = """{"inputs": ["", "ñ", "ñ", "\u00f1", "line\nbreak"], "expectedOutputs": ["🌎", ""]}"""

        assertEquals(
            TestCaseDefinition(listOf("", "ñ", "ñ", "ñ", "line\nbreak"), listOf("🌎", "")),
            decoder.decode(json),
        )
        assertEquals(
            TestCaseDefinition(emptyList(), emptyList()),
            decoder.decode("""{"inputs": [], "expectedOutputs": []}"""),
        )
    }

    @Test
    fun `missing unknown or identity fields are rejected instead of ignored`() {
        val bodies =
            listOf(
                """{"inputs": []}""",
                """{"expectedOutputs": []}""",
                """{"inputs": [], "expectedOutputs": [], "name": "extra"}""",
                """{"inputs": [], "expectedOutputs": [], "owner": "dev-juan"}""",
                """{"inputs": [], "expectedOutputs": [], "actorId": "dev-juan"}""",
            )

        bodies.forEach { assertNull(decoder.decode(it), it) }
    }

    @Test
    fun `only arrays of strings are accepted`() {
        val bodies =
            listOf(
                """{"inputs": null, "expectedOutputs": []}""",
                """{"inputs": "a", "expectedOutputs": []}""",
                """{"inputs": [null], "expectedOutputs": []}""",
                """{"inputs": [1], "expectedOutputs": []}""",
                """{"inputs": [true], "expectedOutputs": []}""",
                """{"inputs": [["a"]], "expectedOutputs": []}""",
                """{"inputs": [], "expectedOutputs": [{"text": "a"}]}""",
            )

        bodies.forEach { assertNull(decoder.decode(it), it) }
    }

    @Test
    fun `anything that is not exactly one JSON object is rejected`() {
        val bodies =
            listOf(
                "",
                "null",
                "[]",
                "\"text\"",
                """{"inputs": [], "expectedOutputs": []} {}""",
                """{"inputs": [], "inputs": [], "expectedOutputs": []}""",
                """{"inputs": [], "expectedOutputs": [""",
            )

        bodies.forEach { assertNull(decoder.decode(it), it) }
    }
}
