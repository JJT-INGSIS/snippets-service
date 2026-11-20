package com.jjt.ingsis.snippets.metadata

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

private const val MAX_RECOVERY_BATCH = 100

@Component
class OutboxRecovery(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
) {
    // Explicit operator action after Redis reconstruction, with publishers/workers paused.
    fun requeueBatch(limit: Int): Int {
        require(limit in 1..MAX_RECOVERY_BATCH)
        return transaction.commit {
            jdbc.update(
                "WITH recoverable AS (SELECT o.event_id FROM event_outbox o " +
                    "JOIN background_jobs j ON j.id = o.job_id " +
                    "WHERE o.published_at IS NOT NULL AND j.finished_at IS NULL " +
                    "AND j.next_attempt_at <= CURRENT_TIMESTAMP " +
                    "AND (j.lease_until IS NULL OR j.lease_until <= CURRENT_TIMESTAMP) " +
                    "AND (o.lease_until IS NULL OR o.lease_until <= CURRENT_TIMESTAMP) " +
                    "ORDER BY o.created_at, o.event_id LIMIT ? FOR UPDATE OF o, j SKIP LOCKED) " +
                    "UPDATE event_outbox o SET published_at = NULL, redis_record_id = NULL, " +
                    "lease_token = NULL, lease_until = NULL, next_attempt_at = CURRENT_TIMESTAMP " +
                    "FROM recoverable r WHERE o.event_id = r.event_id",
                limit,
            )
        }
    }
}
