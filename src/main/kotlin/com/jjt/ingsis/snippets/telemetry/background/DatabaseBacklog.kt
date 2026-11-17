package com.jjt.ingsis.snippets.telemetry.background

import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet

private const val REASONS_LIMIT = 200

private const val OUTBOX_SQL = """
SELECT count(*) AS pending,
       COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0) AS oldest,
       count(*) FILTER (WHERE attempts > 0) AS retrying,
       COALESCE(string_agg(DISTINCT last_error, ',' ORDER BY last_error) FILTER (WHERE last_error IS NOT NULL), '')
           AS reasons
FROM event_outbox
WHERE published_at IS NULL
"""

private const val JOBS_SQL = """
SELECT j.reaction,
       j.state,
       count(*) AS total,
       COALESCE(EXTRACT(EPOCH FROM now() - min(j.created_at)
           FILTER (WHERE j.next_attempt_at <= now() AND (j.lease_until IS NULL OR j.lease_until < now()))), 0)
           AS oldest_eligible,
       count(*) FILTER (WHERE j.next_attempt_at > now()) AS backoff,
       COALESCE(EXTRACT(EPOCH FROM now() - min(GREATEST(j.updated_at, COALESCE(p.last_item, j.updated_at)))), 0)
           AS stalest_progress
FROM background_jobs j
LEFT JOIN LATERAL (
    SELECT max(i.updated_at) AS last_item FROM background_job_items i WHERE i.job_id = j.id
) p ON TRUE
WHERE j.finished_at IS NULL
GROUP BY j.reaction, j.state
"""

private const val FINISHED_SQL = """
SELECT reaction, state, count(*) AS total
FROM background_jobs
WHERE finished_at >= now() - make_interval(secs => ?)
GROUP BY reaction, state
"""

private const val CAPACITY_SQL = """
SELECT count(*) FILTER (WHERE kind = 'BACKGROUND') AS held_background,
       count(*) FILTER (WHERE state = 'UNKNOWN') AS unknown,
       COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0) AS oldest
FROM execution_permits
WHERE state <> 'RELEASED'
"""

data class OutboxBacklog(
    val pending: Long,
    val oldestPendingSeconds: Long,
    val retrying: Long,
    val failingReasons: String,
)

data class JobGroup(
    val reaction: String,
    val state: String,
    val count: Long,
    val oldestEligibleSeconds: Long,
    val backoff: Long,
    val stalestProgressSeconds: Long,
)

data class FinishedGroup(
    val reaction: String,
    val state: String,
    val count: Long,
)

data class CapacityBacklog(
    val heldBackground: Long,
    val unknownPermits: Long,
    val oldestHeldSeconds: Long,
)

class DatabaseBacklog(
    private val jdbc: JdbcTemplate,
    private val settings: BackgroundSamplerSettings,
) {
    fun outbox(): OutboxBacklog =
        single(OUTBOX_SQL) { row ->
            OutboxBacklog(
                pending = row.getLong("pending"),
                oldestPendingSeconds = row.getLong("oldest"),
                retrying = row.getLong("retrying"),
                failingReasons = row.getString("reasons").take(REASONS_LIMIT),
            )
        }

    fun jobs(): List<JobGroup> =
        jdbc.query(JOBS_SQL) { row, _ ->
            JobGroup(
                reaction = row.getString("reaction"),
                state = row.getString("state"),
                count = row.getLong("total"),
                oldestEligibleSeconds = row.getLong("oldest_eligible"),
                backoff = row.getLong("backoff"),
                stalestProgressSeconds = row.getLong("stalest_progress"),
            )
        }

    fun finished(): List<FinishedGroup> =
        jdbc.query(FINISHED_SQL, { row, _ ->
            FinishedGroup(row.getString("reaction"), row.getString("state"), row.getLong("total"))
        }, settings.finishedWindow.seconds)

    fun capacity(): CapacityBacklog =
        single(CAPACITY_SQL) { row ->
            CapacityBacklog(
                heldBackground = row.getLong("held_background"),
                unknownPermits = row.getLong("unknown"),
                oldestHeldSeconds = row.getLong("oldest"),
            )
        }

    private fun <T : Any> single(
        sql: String,
        mapper: (ResultSet) -> T,
    ): T = jdbc.queryForObject(sql) { row, _ -> mapper(row) }
}
