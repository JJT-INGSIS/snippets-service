package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.background.DomainEvent
import com.jjt.ingsis.snippets.background.EventDelivery
import com.jjt.ingsis.snippets.background.EventDeliveryStore
import com.jjt.ingsis.snippets.background.OutboxSettings
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

private const val MAX_ERROR_LENGTH = 500

@Repository
class JdbcEventDeliveries(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
    private val settings: OutboxSettings,
) : EventDeliveryStore {
    private val json = JsonMapper.builder().findAndAddModules().build()

    override fun claim(): EventDelivery? =
        transaction
            .commit {
                jdbc
                    .query(
                        "WITH candidate AS (SELECT event_id FROM event_outbox " +
                            "WHERE published_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP " +
                            "AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP) " +
                            "ORDER BY next_attempt_at, created_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED) " +
                            "UPDATE event_outbox o SET lease_token = ?, " +
                            "lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), " +
                            "attempts = attempts + 1 " +
                            "FROM candidate c WHERE o.event_id = c.event_id " +
                            "RETURNING o.envelope, o.lease_token, o.attempts",
                        { row, _ ->
                            EventDelivery(
                                event = json.readValue(row.getString("envelope"), DomainEvent::class.java),
                                leaseToken = row.getObject("lease_token", UUID::class.java),
                                attempt = row.getInt("attempts"),
                            )
                        },
                        UUID.randomUUID(),
                        settings.lease.toMillis(),
                    )
            }.singleOrNull()

    override fun published(
        delivery: EventDelivery,
        recordId: String,
    ): Boolean =
        transaction.commit {
            jdbc.update(
                "UPDATE event_outbox SET published_at = CURRENT_TIMESTAMP, redis_record_id = ?, " +
                    "lease_token = NULL, lease_until = NULL, last_error = NULL " +
                    "WHERE event_id = ? AND lease_token = ? AND lease_until > CURRENT_TIMESTAMP",
                recordId,
                delivery.event.eventId,
                delivery.leaseToken,
            ) == 1
        }

    override fun retry(
        delivery: EventDelivery,
        nextAttempt: Instant,
        reason: String,
    ): Boolean =
        transaction.commit {
            jdbc.update(
                "UPDATE event_outbox SET next_attempt_at = ?, last_error = ?, lease_token = NULL, lease_until = NULL " +
                    "WHERE event_id = ? AND lease_token = ? AND lease_until > CURRENT_TIMESTAMP",
                Timestamp.from(nextAttempt),
                reason.take(MAX_ERROR_LENGTH),
                delivery.event.eventId,
                delivery.leaseToken,
            ) == 1
        }
}
