package com.jjt.ingsis.snippets.background

import java.time.Instant
import java.util.UUID

enum class EventType {
    FormattingRulesChanged,
    LintRulesChanged,
    ParserVersionActivated,
    SnippetUpdated,
}

data class DomainEvent(
    val eventId: UUID,
    val jobId: UUID,
    val eventType: EventType,
    val correlationId: UUID,
    val occurredAt: Instant,
    val scope: Map<String, String>,
    val revisions: Map<String, String>,
    val schemaVersion: Int = 1,
) {
    init {
        require(schemaVersion == 1)
        require(scope.isNotEmpty() && revisions.isNotEmpty())
        require(scope.all { (key, value) -> key.isNotBlank() && value.isNotBlank() })
        require(revisions.all { (key, value) -> key.isNotBlank() && value.isNotBlank() })
    }
}

data class NewBackgroundJob(
    val event: DomainEvent,
    val reaction: String,
    val initiatedBy: String? = null,
) {
    init {
        require(reaction.isNotBlank())
        require(initiatedBy == null || initiatedBy.isNotBlank())
    }
}

data class ConfirmedJobChange<T : Any>(
    val value: T,
    val job: NewBackgroundJob,
)

data class NewJobItem(
    val id: UUID,
    val jobId: UUID,
    val key: String,
    val revisions: Map<String, String>,
    val snapshotReference: String? = null,
) {
    init {
        require(key.isNotBlank() && revisions.isNotEmpty())
    }
}
