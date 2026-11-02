package com.jjt.ingsis.snippets.background

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class DomainEventTest {
    private val json = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `v1 envelope round trips every agreed event type with stable identifiers and revisions`() {
        EventType.entries.forEach { type ->
            val event = newJob().event.copy(eventType = type, scope = mapOf("ownerId" to "usuario-á"))
            val encoded = json.writeValueAsString(event)
            val decoded = json.readValue(encoded, DomainEvent::class.java)
            assertThat(decoded).isEqualTo(event)
            assertThat(encoded).contains("\"eventType\":\"${type.name}\"", "\"schemaVersion\":1")
            assertThat(encoded).doesNotContain("_class", "javaClass")
        }
    }

    @Test
    fun `envelope rejects unsupported schemas and empty scope or revisions`() {
        val event = newJob().event
        assertThatThrownBy { event.copy(schemaVersion = 2) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { event.copy(scope = emptyMap()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { event.copy(revisions = emptyMap()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { event.copy(scope = mapOf("ownerId" to " ")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
