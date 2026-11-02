package com.jjt.ingsis.snippets.lintingrules

import com.jjt.ingsis.snippets.background.DomainEvent
import com.jjt.ingsis.snippets.background.EventType
import com.jjt.ingsis.snippets.background.NewBackgroundJob
import com.jjt.ingsis.snippets.language.ProcessorRevision
import java.time.Instant
import java.util.UUID

const val LINT_OWNED_SNIPPETS = "lint-owned-snippets"

object LintingRulesChangedJob {
    fun of(
        scope: LintingScope,
        configurationId: UUID,
        selection: LintingSelection,
        processor: ProcessorRevision,
    ): NewBackgroundJob =
        NewBackgroundJob(
            event =
                DomainEvent(
                    eventId = UUID.randomUUID(),
                    jobId = UUID.randomUUID(),
                    eventType = EventType.LintRulesChanged,
                    correlationId = UUID.randomUUID(),
                    occurredAt = Instant.now(),
                    scope =
                        mapOf(
                            "ownerId" to scope.ownerId,
                            "language" to scope.language,
                            "version" to scope.version,
                            "configurationId" to configurationId.toString(),
                        ),
                    revisions = mapOf("lintRules" to selection.revision, "lintProcessor" to processor.value),
                ),
            reaction = LINT_OWNED_SNIPPETS,
            initiatedBy = scope.ownerId,
        )
}
