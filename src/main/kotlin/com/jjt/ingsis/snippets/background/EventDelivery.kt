package com.jjt.ingsis.snippets.background

import java.time.Instant
import java.util.UUID

data class EventDelivery(
    val event: DomainEvent,
    val leaseToken: UUID,
    val attempt: Int,
)

interface EventDeliveryStore {
    fun claim(): EventDelivery?

    fun published(
        delivery: EventDelivery,
        recordId: String,
    ): Boolean

    fun retry(
        delivery: EventDelivery,
        nextAttempt: Instant,
        reason: String,
    ): Boolean
}

fun interface EventStream {
    fun publish(event: DomainEvent): String
}

class EventStreamUnavailable(
    cause: Throwable,
) : RuntimeException("Event stream unavailable", cause)
