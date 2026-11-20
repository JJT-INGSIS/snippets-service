package com.jjt.ingsis.snippets.telemetry

import com.jjt.ingsis.snippets.background.DomainEvent
import com.jjt.ingsis.snippets.background.EventType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.time.Instant
import java.util.UUID

private const val ATTEMPT_NUMBER = 3

class BackgroundContextTest {
    @AfterEach
    fun clearContext() = MDC.clear()

    private val event =
        DomainEvent(
            eventId = UUID.randomUUID(),
            jobId = UUID.randomUUID(),
            eventType = EventType.FormattingRulesChanged,
            correlationId = UUID.randomUUID(),
            occurredAt = Instant.now(),
            scope = mapOf("ownerId" to "dev-juan"),
            revisions = mapOf("formattingRules" to "r1"),
        )

    @Test
    fun `logs during the work carry the event, job, correlation and attempt`() {
        val seen =
            BackgroundContext.open(event, ATTEMPT_NUMBER).use {
                listOf(CORRELATION_ID, EVENT_ID, JOB_ID, ATTEMPT).associateWith { key -> MDC.get(key) }
            }

        assertThat(seen)
            .containsEntry(CORRELATION_ID, event.correlationId.toString())
            .containsEntry(EVENT_ID, event.eventId.toString())
            .containsEntry(JOB_ID, event.jobId.toString())
            .containsEntry(ATTEMPT, ATTEMPT_NUMBER.toString())
    }

    @Test
    fun `closing removes only its own keys`() {
        MDC.put(REQUEST_ID, "kept")

        BackgroundContext.open(event, 1).close()

        listOf(CORRELATION_ID, EVENT_ID, JOB_ID, ATTEMPT).forEach { assertThat(MDC.get(it)).isNull() }
        assertThat(MDC.get(REQUEST_ID)).isEqualTo("kept")
    }
}
