package com.jjt.ingsis.snippets.telemetry.background

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException

private const val SERVICE = "snippets-service"
private const val ALL = "all"

class BackgroundSampler(
    private val database: DatabaseBacklog,
    private val stream: StreamBacklog,
    private val sink: SampleSink,
    private val settings: BackgroundSamplerSettings,
) {
    private val logger = LoggerFactory.getLogger(BackgroundSampler::class.java)

    fun sample() {
        val samples =
            measured("outbox") { listOf(outboxRow(database.outbox())) } +
                measured("jobs") { jobRows(database.jobs()) } +
                measured("jobs-finished") { database.finished().map(::finishedRow) } +
                measured("capacity") { listOf(capacityRow(database.capacity())) } +
                measured("stream") { listOf(streamRow(stream.read())) }
        sink.record(samples)
    }

    private fun measured(
        aspect: String,
        rows: () -> List<Map<String, Any>>,
    ): List<BackgroundSample> {
        val measured =
            try {
                rows().map { it + ("samplerError" to false) }
            } catch (failure: DataAccessException) {
                failed(aspect, failure)
            } catch (failure: IllegalStateException) {
                failed(aspect, failure)
            }
        return measured.map { BackgroundSample(aspect, it + identity(aspect)) }
    }

    private fun failed(
        aspect: String,
        failure: Exception,
    ): List<Map<String, Any>> {
        logger.warn("Background sample failed aspect={} failure={}", aspect, failure.javaClass.simpleName)
        return listOf(mapOf("samplerError" to true))
    }

    private fun identity(aspect: String): Map<String, Any> =
        mapOf("aspect" to aspect, "environment" to settings.environment, "service" to SERVICE)
}

fun outboxRow(backlog: OutboxBacklog): Map<String, Any> =
    mapOf(
        "pending" to backlog.pending,
        "oldestPendingSeconds" to backlog.oldestPendingSeconds,
        "retrying" to backlog.retrying,
        "failingReasons" to backlog.failingReasons,
    )

fun jobRows(groups: List<JobGroup>): List<Map<String, Any>> {
    val total =
        mapOf(
            "reaction" to ALL,
            "state" to ALL,
            "count" to groups.sumOf { it.count },
            "oldestEligibleSeconds" to (groups.maxOfOrNull { it.oldestEligibleSeconds } ?: 0L),
            "backoff" to groups.sumOf { it.backoff },
            "stalestProgressSeconds" to (groups.maxOfOrNull { it.stalestProgressSeconds } ?: 0L),
        )
    return listOf(total) +
        groups.map { group ->
            mapOf(
                "reaction" to group.reaction,
                "state" to group.state,
                "count" to group.count,
                "oldestEligibleSeconds" to group.oldestEligibleSeconds,
                "backoff" to group.backoff,
                "stalestProgressSeconds" to group.stalestProgressSeconds,
            )
        }
}

fun finishedRow(group: FinishedGroup): Map<String, Any> =
    mapOf("reaction" to group.reaction, "state" to group.state, "count" to group.count)

fun capacityRow(backlog: CapacityBacklog): Map<String, Any> =
    mapOf(
        "heldBackground" to backlog.heldBackground,
        "unknownPermits" to backlog.unknownPermits,
        "oldestHeldSeconds" to backlog.oldestHeldSeconds,
    )

fun streamRow(backlog: StreamGroupBacklog): Map<String, Any> =
    mapOf(
        "streamExists" to backlog.streamExists,
        "groupExists" to backlog.groupExists,
        "lagKnown" to (backlog.lag != null),
        "pending" to backlog.pending,
        "oldestPendingIdleSeconds" to backlog.oldestPendingIdleSeconds,
        "consumers" to backlog.consumers,
    ) + listOfNotNull(backlog.lag?.let { "lag" to it }, backlog.entriesRead?.let { "entriesRead" to it })
