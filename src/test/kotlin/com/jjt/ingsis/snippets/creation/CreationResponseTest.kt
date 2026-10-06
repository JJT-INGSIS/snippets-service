package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.metadata.SnippetState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class CreationResponseTest {
    @Test
    fun `future response matches documented example without exposing persistence or code`() {
        val mapper = JsonMapper.builder().build()
        val document = Files.readString(Path.of("docs/creation.md"))
        val json = Regex("```json\\s*([\\s\\S]*?)```").findAll(document).toList()[1].groupValues[1]
        val id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        val response =
            checkNotNull(CreationResponse.fromConfirmed(reservedMetadata(SnippetState.CONFIRMED).copy(id = id)))
        val serialized = mapper.readTree(mapper.writeValueAsString(response))
        assertEquals(mapper.readTree(json), serialized)
        assertEquals(setOf("id", "name", "description", "language", "version"), serialized.propertyNames().toSet())
    }
}
