package com.jjt.ingsis.snippets.update

import com.jjt.ingsis.snippets.creation.DraftInput
import com.jjt.ingsis.snippets.creation.DraftJsonDecoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

class UpdateContractTest {
    private val decoder = DraftJsonDecoder()
    private val mapper = JsonMapper.builder().build()
    private val example =
        Regex(
            "```json\\s*([\\s\\S]*?)```",
        ).find(Files.readString(Path.of("docs/update.md")))!!.groupValues[1]

    @Test
    fun `documented replacement requires all fields and rejects ownership`() {
        val accepted = assertInstanceOf(DraftInput.Accepted::class.java, decoder.decode(example))
        assertEquals("1.1", accepted.draft.version)
        assertEquals("println(2);", accepted.draft.code)
        listOf("name", "language", "version", "code").forEach { field ->
            val missing = mapper.readTree(example) as ObjectNode
            missing.remove(field)
            assertInstanceOf(DraftInput.Rejected::class.java, decoder.decode(mapper.writeValueAsString(missing)))
            missing.put(field, 1)
            assertInstanceOf(DraftInput.Rejected::class.java, decoder.decode(mapper.writeValueAsString(missing)))
        }
        val withOwner = mapper.readTree(example) as ObjectNode
        withOwner.put("ownerId", "forged")
        assertInstanceOf(DraftInput.Rejected::class.java, decoder.decode(mapper.writeValueAsString(withOwner)))
    }

    @Test
    fun `absent and null description both clear it but empty stays distinct`() {
        val nullable = assertInstanceOf(DraftInput.Accepted::class.java, decoder.decode(example))
        val node = mapper.readTree(example) as ObjectNode
        node.remove("description")
        assertEquals(nullable, decoder.decode(mapper.writeValueAsString(node)))
        node.put("description", "")
        assertEquals(
            nullable.draft.copy(description = ""),
            assertInstanceOf(DraftInput.Accepted::class.java, decoder.decode(mapper.writeValueAsString(node))).draft,
        )
    }
}
