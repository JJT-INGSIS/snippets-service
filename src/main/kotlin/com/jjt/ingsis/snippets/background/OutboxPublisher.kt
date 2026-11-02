package com.jjt.ingsis.snippets.background

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow
import kotlin.random.Random

private const val MAX_BACKOFF_EXPONENT = 30
private const val JITTER_MIN = 0.5

class OutboxPublisher(
    private val deliveries: EventDeliveryStore,
    private val stream: EventStream,
    private val settings: OutboxSettings,
) {
    private val running = AtomicBoolean(false)
    private val logger = LoggerFactory.getLogger(OutboxPublisher::class.java)

    fun publishBatch(): Int {
        if (!running.compareAndSet(false, true)) return 0
        return try {
            publishAvailable()
        } finally {
            running.set(false)
        }
    }

    private fun publishAvailable(): Int {
        var published = 0
        repeat(settings.batchSize) {
            val delivery = deliveries.claim() ?: return published
            if (publish(delivery)) published++
        }
        return published
    }

    private fun publish(delivery: EventDelivery): Boolean {
        val event = delivery.event
        val recordId =
            try {
                stream.publish(event)
            } catch (_: EventStreamUnavailable) {
                deliveries.retry(delivery, nextAttempt(delivery.attempt), "REDIS_WRITE_UNAVAILABLE")
                logger.warn(
                    "Outbox retry eventId={} jobId={} correlationId={} attempt={}",
                    event.eventId,
                    event.jobId,
                    event.correlationId,
                    delivery.attempt,
                )
                return false
            }
        val recorded = deliveries.published(delivery, recordId)
        logger.info(
            "Outbox publication eventId={} jobId={} correlationId={} attempt={} recorded={}",
            event.eventId,
            event.jobId,
            event.correlationId,
            delivery.attempt,
            recorded,
        )
        return recorded
    }

    private fun nextAttempt(attempt: Int): Instant {
        val exponent = (attempt - 1).coerceIn(0, MAX_BACKOFF_EXPONENT)
        val cap =
            minOf(settings.maxRetryDelay.toMillis().toDouble(), settings.retryDelay.toMillis() * 2.0.pow(exponent))
        return Instant.now().plusMillis((cap * Random.nextDouble(JITTER_MIN, 1.0)).toLong().coerceAtLeast(1))
    }
}
