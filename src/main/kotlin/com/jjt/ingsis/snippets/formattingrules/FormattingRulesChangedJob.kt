package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.background.DomainEvent
import com.jjt.ingsis.snippets.background.EventType
import com.jjt.ingsis.snippets.background.NewBackgroundJob
import com.jjt.ingsis.snippets.language.ProcessorRevision
import java.time.Instant
import java.util.UUID

const val FORMAT_OWNED_SNIPPETS = "format-owned-snippets"

object FormattingRulesChangedJob {
    fun of(
        scope: FormattingScope,
        configurationId: UUID,
        selection: FormattingSelection,
        processor: ProcessorRevision,
    ): NewBackgroundJob =
        NewBackgroundJob(
            event =
                DomainEvent(
                    eventId = UUID.randomUUID(),
                    jobId = UUID.randomUUID(),
                    eventType = EventType.FormattingRulesChanged,
                    correlationId = UUID.randomUUID(),
                    occurredAt = Instant.now(),
                    scope =
                        mapOf(
                            "ownerId" to scope.ownerId,
                            "language" to scope.language,
                            "version" to scope.version,
                            "configurationId" to configurationId.toString(),
                        ),
                    revisions = mapOf("formattingRules" to selection.revision, "formatProcessor" to processor.value),
                ),
            reaction = FORMAT_OWNED_SNIPPETS,
            initiatedBy = scope.ownerId,
        )
}
