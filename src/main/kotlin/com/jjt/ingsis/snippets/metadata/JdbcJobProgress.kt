package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.background.DomainEvent
import com.jjt.ingsis.snippets.background.ItemState
import com.jjt.ingsis.snippets.background.JobLease
import com.jjt.ingsis.snippets.background.JobProgress
import com.jjt.ingsis.snippets.background.JobProgressStore
import com.jjt.ingsis.snippets.background.JobState
import com.jjt.ingsis.snippets.background.JobStep
import com.jjt.ingsis.snippets.background.LostJobLease
import com.jjt.ingsis.snippets.background.NewJobItem
import com.jjt.ingsis.snippets.background.StoredJob
import com.jjt.ingsis.snippets.background.WorkItem
import com.jjt.ingsis.snippets.background.WorkerSettings
import com.jjt.ingsis.snippets.background.delayMillis
import com.jjt.ingsis.snippets.background.safeJobReason
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.util.UUID

// One cohesive implementation of the job persistence boundary; SQL does not enter handlers.
@Suppress("TooManyFunctions")
@Repository
class JdbcJobProgress(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
    private val settings: WorkerSettings,
    private val records: JdbcJobRecords,
) : JobProgressStore {
    private val json = JsonMapper.builder().findAndAddModules().build()

    override fun <T : Any> persist(
        lease: JobLease,
        operation: () -> T,
    ): T = owned(lease, operation)

    override fun find(id: UUID): StoredJob? =
        transaction.read {
            jdbc
                .query(
                    "SELECT j.*, o.envelope FROM background_jobs j JOIN event_outbox o ON o.job_id = " +
                        "j.id WHERE j.id = ?",
                    { row, _ -> read(row) },
                    id,
                ).singleOrNull()
        }

    override fun claim(id: UUID): StoredJob? =
        transaction
            .commit {
                val changed =
                    jdbc.update(
                        "UPDATE background_jobs SET lease_token = ?, " +
                            "lease_until = clock_timestamp() + (? * INTERVAL '1 millisecond'), " +
                            "state = CASE WHEN state = 'UNKNOWN' THEN state ELSE 'RUNNING' END, " +
                            "updated_at = clock_timestamp() " +
                            "WHERE id = ? AND finished_at IS NULL AND next_attempt_at <= clock_timestamp() " +
                            "AND (lease_until IS NULL OR lease_until <= clock_timestamp())",
                        UUID.randomUUID(),
                        settings.lease.toMillis(),
                        id,
                    )
                if (changed == 1) listOfNotNull(find(id)) else emptyList()
            }.singleOrNull()

    override fun renew(lease: JobLease): Boolean =
        transaction.commit {
            jdbc.update(
                "UPDATE background_jobs SET lease_until = clock_timestamp() + (? * INTERVAL '1 millisecond') " +
                    "WHERE id = ? AND lease_token = ? AND lease_until > clock_timestamp() AND " +
                    "finished_at IS NULL",
                settings.lease.toMillis(),
                lease.jobId,
                lease.token,
            ) == 1
        }

    fun <T : Any> owned(
        lease: JobLease,
        operation: () -> T,
    ): T =
        transaction.join {
            val found =
                jdbc.queryForList(
                    "SELECT id FROM background_jobs WHERE id = ? AND lease_token = ? " +
                        "AND lease_until > clock_timestamp() AND finished_at IS NULL FOR UPDATE",
                    lease.jobId,
                    lease.token,
                )
            if (found.isEmpty()) throw LostJobLease()
            operation()
        }

    override fun defer(id: UUID) =
        transaction.commit {
            jdbc.update(
                "UPDATE background_jobs SET next_attempt_at = clock_timestamp() + " +
                    "(? * INTERVAL '1 millisecond'), last_error = 'HANDLER_UNAVAILABLE' " +
                    "WHERE id = ? AND finished_at IS NULL AND next_attempt_at <= clock_timestamp() " +
                    "AND (lease_until IS NULL OR lease_until <= clock_timestamp())",
                settings.missingHandlerDelay.toMillis(),
                id,
            )
            Unit
        }

    override fun finish(
        lease: JobLease,
        step: JobStep,
    ): Boolean =
        owned(lease) {
            val job = checkNotNull(find(lease.jobId))
            val held =
                jdbc.queryForObject(
                    "SELECT count(*) FROM execution_permits WHERE job_id = ? AND state <> 'RELEASED'",
                    Int::class.java,
                    lease.jobId,
                ) != 0
            val status = status(job, step, held)
            val terminal = status in setOf(JobState.COMPLETED, JobState.PARTIAL, JobState.FAILED, JobState.SUPERSEDED)
            val reason = reason(step, held)
            val next =
                if (status == JobState.RETRY || status == JobState.UNKNOWN) {
                    settings.retries.delayMillis(job.attempts + 1)
                } else {
                    1000L
                }
            jdbc.update(
                "UPDATE background_jobs SET state = ?, next_attempt_at = clock_timestamp() + " +
                    "(? * INTERVAL '1 millisecond'), attempts = ?, " +
                    "finished_at = CASE WHEN ? THEN clock_timestamp() ELSE NULL END, " +
                    "last_error = ?, lease_token = NULL, lease_until = NULL, " +
                    "updated_at = clock_timestamp() " +
                    "WHERE id = ? AND lease_token = ?",
                status.name,
                next,
                if (step is JobStep.Retry || status == JobState.UNKNOWN) job.attempts + 1 else 0,
                terminal,
                reason,
                lease.jobId,
                lease.token,
            )
            if (status == JobState.UNKNOWN) {
                jdbc.update(
                    "UPDATE background_job_items SET state = 'UNKNOWN' WHERE job_id = ? AND state = 'RUNNING'",
                    lease.jobId,
                )
                jdbc.update(
                    "UPDATE execution_permits SET state = 'UNKNOWN' WHERE job_id = ? AND state <> 'RELEASED'",
                    lease.jobId,
                )
            }
            terminal || status == JobState.UNKNOWN
        }

    private fun reason(
        step: JobStep,
        held: Boolean,
    ): String? =
        when (step) {
            is JobStep.Retry -> safeJobReason(step.reason)
            is JobStep.Failed -> safeJobReason(step.reason)
            is JobStep.Unknown -> safeJobReason(step.reason)
            JobStep.Complete, JobStep.Yield -> if (held) "RECONCILIATION_REQUIRED" else null
        }

    private fun status(
        job: StoredJob,
        step: JobStep,
        held: Boolean,
    ): JobState {
        if (held || step is JobStep.Unknown) return JobState.UNKNOWN
        return when (step) {
            JobStep.Complete -> {
                val counts = checkNotNull(progress(job.event.jobId))
                if (!job.selectionComplete || counts.pending > 0) {
                    JobState.PENDING
                } else if (counts.failed > 0) {
                    JobState.PARTIAL
                } else {
                    JobState.COMPLETED
                }
            }

            JobStep.Yield -> {
                JobState.PENDING
            }

            is JobStep.Retry -> {
                if (job.attempts + 1 >= settings.retries.attempts) JobState.FAILED else JobState.RETRY
            }

            is JobStep.Failed -> {
                JobState.FAILED
            }

            is JobStep.Unknown -> {
                JobState.UNKNOWN
            }
        }
    }

    override fun select(
        lease: JobLease,
        items: List<NewJobItem>,
        cursor: String?,
        complete: Boolean,
    ) = owned(lease) {
        require(items.size <= settings.itemBatchSize && items.all { it.jobId == lease.jobId })
        val job = checkNotNull(find(lease.jobId))
        check(!job.selectionComplete || items.isEmpty()) { "Selection already closed" }
        items.forEach { records.insertItem(it) }
        jdbc.update(
            "UPDATE background_jobs SET selection_cursor = ?, selection_complete = ? WHERE id = ?",
            cursor,
            job.selectionComplete || complete,
            lease.jobId,
        )
        Unit
    }

    override fun items(lease: JobLease): List<WorkItem> =
        owned(lease) {
            jdbc.query(
                "WITH chosen AS (SELECT id FROM background_job_items WHERE job_id = ? " +
                    "AND state IN ('PENDING', 'RETRY', 'RUNNING') AND next_attempt_at <= clock_timestamp() " +
                    "ORDER BY created_at, id LIMIT ? FOR UPDATE) " +
                    "UPDATE background_job_items i SET state = 'RUNNING', attempts = attempts + 1, " +
                    "updated_at = clock_timestamp() " +
                    "FROM chosen c WHERE i.id = c.id RETURNING i.*",
                { row, _ -> readItem(row) },
                lease.jobId,
                1,
            )
        }

    override fun completeItem(
        lease: JobLease,
        itemId: UUID,
        result: String?,
        state: ItemState,
    ) = owned(lease) {
        require(state in setOf(ItemState.COMPLETED, ItemState.FAILED, ItemState.SUPERSEDED))
        check(
            jdbc.update(
                "UPDATE background_job_items SET state = ?, result_reference = ?, last_error = NULL, " +
                    "updated_at = clock_timestamp() " +
                    "WHERE id = ? AND job_id = ? AND state IN ('RUNNING', 'UNKNOWN')",
                state.name,
                result,
                itemId,
                lease.jobId,
            ) == 1,
        )
    }

    override fun retryItem(
        lease: JobLease,
        itemId: UUID,
        reason: String,
    ) = owned(lease) {
        val attempt =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT attempts FROM background_job_items WHERE id = ? AND job_id = ?",
                    Int::class.java,
                    itemId,
                    lease.jobId,
                ),
            )
        jdbc.update(
            "UPDATE background_job_items SET state = ?, next_attempt_at = clock_timestamp() + " +
                "(? * INTERVAL '1 millisecond'), last_error = ?, " +
                "updated_at = clock_timestamp() " +
                "WHERE id = ? AND job_id = ? AND state = 'RUNNING'",
            if (attempt >= settings.retries.attempts) "FAILED" else "RETRY",
            settings.retries.delayMillis(attempt),
            safeJobReason(reason),
            itemId,
            lease.jobId,
        )
        Unit
    }

    override fun progress(id: UUID): JobProgress? =
        transaction.read {
            jdbc
                .query(
                    "SELECT j.id, j.state, j.selection_complete, " +
                        "count(i.id) FILTER (WHERE i.state IN ('COMPLETED','SUPERSEDED')) AS processed, " +
                        "count(i.id) FILTER (WHERE i.state = 'FAILED') AS failed, " +
                        "count(i.id) FILTER (WHERE i.state IN " +
                        "('PENDING','RUNNING','RETRY','UNKNOWN')) AS pending " +
                        "FROM background_jobs j LEFT JOIN background_job_items i ON i.job_id = j.id " +
                        "WHERE j.id = ? GROUP BY j.id",
                    { row, _ ->
                        JobProgress(
                            id = row.getObject("id", UUID::class.java),
                            status = JobState.valueOf(row.getString("state")),
                            processed = row.getLong("processed"),
                            failed = row.getLong("failed"),
                            pending = row.getLong("pending"),
                            selectionComplete = row.getBoolean("selection_complete"),
                        )
                    },
                    id,
                ).singleOrNull()
        }

    override fun reconciliations(): List<UUID> =
        transaction.read {
            jdbc.query(
                "SELECT id FROM background_jobs WHERE state = 'UNKNOWN' AND next_attempt_at <= " +
                    "clock_timestamp() " +
                    "AND (lease_until IS NULL OR lease_until <= clock_timestamp()) ORDER BY " +
                    "next_attempt_at LIMIT ?",
                { row, _ -> row.getObject("id", UUID::class.java) },
                settings.batchSize,
            )
        }

    private fun read(row: ResultSet): StoredJob {
        val id = row.getObject("id", UUID::class.java)
        return StoredJob(
            event = json.readValue(row.getString("envelope"), DomainEvent::class.java),
            state = JobState.valueOf(row.getString("state")),
            selectionComplete = row.getBoolean("selection_complete"),
            attempts = row.getInt("attempts"),
            nextAttempt = row.getTimestamp("next_attempt_at").toInstant(),
            lease = row.getObject("lease_token", UUID::class.java)?.let { JobLease(id, it) },
            cursor = row.getString("selection_cursor"),
        )
    }

    private fun readItem(row: ResultSet): WorkItem =
        WorkItem(
            id = row.getObject("id", UUID::class.java),
            key = row.getString("item_key"),
            state = ItemState.valueOf(row.getString("state")),
            attempts = row.getInt("attempts"),
            revisions =
                json
                    .readTree(
                        row.getString("revisions"),
                    ).properties()
                    .associate { it.key to it.value.asString() },
            snapshotReference = row.getString("snapshot_reference"),
            resultReference = row.getString("result_reference"),
        )
}
