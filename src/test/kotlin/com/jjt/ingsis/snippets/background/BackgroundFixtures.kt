package com.jjt.ingsis.snippets.background

import java.time.Instant
import java.util.UUID

internal fun newJob(): NewBackgroundJob =
    NewBackgroundJob(
        event =
            DomainEvent(
                eventId = UUID.randomUUID(),
                jobId = UUID.randomUUID(),
                eventType = EventType.SnippetUpdated,
                correlationId = UUID.randomUUID(),
                occurredAt = Instant.now(),
                scope = mapOf("snippetId" to UUID.randomUUID().toString()),
                revisions = mapOf("code" to UUID.randomUUID().toString()),
            ),
        reaction = "automatic-tests",
        initiatedBy = "test-owner",
    )
