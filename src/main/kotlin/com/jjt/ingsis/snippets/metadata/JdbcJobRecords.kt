package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.background.NewBackgroundJob
import com.jjt.ingsis.snippets.background.NewJobItem
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant

private const val MAX_ENVELOPE_BYTES = 16 * 1024

@Repository
class JdbcJobRecords(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
) {
    private val json = JsonMapper.builder().findAndAddModules().build()

    fun insert(job: NewBackgroundJob) =
        transaction.requireCommit {
            val event = job.event
            val envelope = json.writeValueAsString(event)
            require(envelope.toByteArray(Charsets.UTF_8).size <= MAX_ENVELOPE_BYTES) { "Event exceeds 16 KiB" }
            val now = Timestamp.from(event.occurredAt)
            jdbc.update(
                "INSERT INTO background_jobs " +
                    "(id, event_id, reaction, initiated_by, scope, revisions, " +
                    "next_attempt_at, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?)",
                event.jobId,
                event.eventId,
                job.reaction,
                job.initiatedBy,
                json.writeValueAsString(event.scope),
                json.writeValueAsString(event.revisions),
                now,
                now,
                now,
            )
            jdbc.update(
                "INSERT INTO event_outbox (event_id, job_id, envelope, created_at, next_attempt_at) " +
                    "VALUES (?, ?, CAST(? AS jsonb), ?, ?)",
                event.eventId,
                event.jobId,
                envelope,
                now,
                now,
            )
            Unit
        }

    fun insertItem(item: NewJobItem): Boolean =
        transaction.requireCommit {
            val now = Timestamp.from(Instant.now())
            jdbc.update(
                "INSERT INTO background_job_items " +
                    "(id, job_id, item_key, revisions, snapshot_reference, next_attempt_at, created_at, updated_at) " +
                    "VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?) ON CONFLICT (job_id, item_key) DO NOTHING",
                item.id,
                item.jobId,
                item.key,
                json.writeValueAsString(item.revisions),
                item.snapshotReference,
                now,
                now,
                now,
            ) == 1
        }
}
