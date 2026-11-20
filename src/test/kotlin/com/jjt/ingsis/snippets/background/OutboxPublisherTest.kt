package com.jjt.ingsis.snippets.background

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OutboxPublisherTest {
    @Test
    fun `stream failure schedules bounded retry and allows other work to proceed`() {
        val first = newJob().event
        val second = newJob().event
        val pending =
            ArrayDeque(listOf(EventDelivery(first, UUID.randomUUID(), 20), EventDelivery(second, UUID.randomUUID(), 1)))
        val store = TestDeliveryStore(pending)
        val sent = mutableListOf<DomainEvent>()
        val settings = OutboxSettings(retryDelay = Duration.ofMillis(100), maxRetryDelay = Duration.ofMillis(400))
        val stream =
            EventStream {
                if (it == first) throw EventStreamUnavailable(java.io.IOException("Unavailable"))
                sent.add(it)
                "1-0"
            }
        val before = Instant.now()
        assertThat(OutboxPublisher(store, stream, settings).publishBatch()).isEqualTo(1)
        assertThat(sent).containsExactly(second)
        assertThat(store.retries).hasSize(1)
        assertThat(store.retries.single()).isAfter(before).isBeforeOrEqualTo(Instant.now().plusMillis(400))
    }

    @Test
    fun `one cycle publishes at most the configured batch and preserves remaining work`() {
        val events = List(3) { newJob().event }
        val pending = ArrayDeque(events.map { EventDelivery(it, UUID.randomUUID(), 1) })
        val sent = mutableListOf<DomainEvent>()
        val store = TestDeliveryStore(pending)
        val publisher =
            OutboxPublisher(
                store,
                EventStream {
                    sent.add(it)
                    "1-0"
                },
                OutboxSettings(batchSize = 2),
            )
        assertThat(publisher.publishBatch()).isEqualTo(2)
        assertThat(sent).containsExactlyElementsOf(events.take(2))
        assertThat(publisher.publishBatch()).isEqualTo(1)
        assertThat(sent).containsExactlyElementsOf(events)
    }

    @Test
    fun `an overlapping cycle cannot publish while the first cycle is in flight`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pending = ArrayDeque(listOf(EventDelivery(newJob().event, UUID.randomUUID(), 1)))
        val stream =
            EventStream {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                "1-0"
            }
        val publisher = OutboxPublisher(TestDeliveryStore(pending), stream, OutboxSettings())
        Executors.newSingleThreadExecutor().use { executor ->
            val first = executor.submit<Int> { publisher.publishBatch() }
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
                assertThat(publisher.publishBatch()).isZero()
            } finally {
                release.countDown()
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1)
        }
    }
}

private class TestDeliveryStore(
    private val pending: ArrayDeque<EventDelivery>,
) : EventDeliveryStore {
    val retries = mutableListOf<Instant>()

    override fun claim(): EventDelivery? = pending.removeFirstOrNull()

    override fun published(
        delivery: EventDelivery,
        recordId: String,
    ): Boolean = true

    override fun retry(
        delivery: EventDelivery,
        nextAttempt: Instant,
        reason: String,
    ): Boolean {
        retries.add(nextAttempt)
        return true
    }
}
