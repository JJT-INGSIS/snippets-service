package com.jjt.ingsis.snippets.background

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

private const val MAX_WORKER_BATCH = 100
private const val MIN_WORKER_LEASE_MS = 300
private const val DEFAULT_WORKER_LEASE_SECONDS = 30L
private const val DEFAULT_DEFER_MINUTES = 5L
private const val DEFAULT_RETRY_ATTEMPTS = 5
private const val DEFAULT_RETRY_CAP_MINUTES = 5L

@ConfigurationProperties("snippets.worker")
data class WorkerSettings(
    val enabled: Boolean = false,
    val group: String = "snippet-processing-v1",
    val batchSize: Int = 5,
    val itemBatchSize: Int = 10,
    val lease: Duration = Duration.ofSeconds(DEFAULT_WORKER_LEASE_SECONDS),
    val reclaimIdle: Duration = Duration.ofSeconds(DEFAULT_WORKER_LEASE_SECONDS),
    val missingHandlerDelay: Duration = Duration.ofMinutes(DEFAULT_DEFER_MINUTES),
    val retries: WorkerRetries = WorkerRetries(),
) {
    init {
        require(group.isNotBlank())
        require(batchSize in 1..MAX_WORKER_BATCH && itemBatchSize in 1..MAX_WORKER_BATCH)
        require(
            lease.toMillis() >= MIN_WORKER_LEASE_MS && reclaimIdle.toMillis() > 0 && missingHandlerDelay.toMillis() > 0,
        )
    }
}

data class WorkerRetries(
    val attempts: Int = DEFAULT_RETRY_ATTEMPTS,
    val initial: Duration = Duration.ofSeconds(2),
    val maximum: Duration = Duration.ofMinutes(DEFAULT_RETRY_CAP_MINUTES),
) {
    init {
        require(attempts in 1..MAX_WORKER_BATCH && initial.toMillis() > 0 && maximum >= initial)
    }
}

@ConfigurationProperties("snippets.capacity")
data class CapacitySettings(
    val enabled: Boolean = false,
    val activeLimit: Int = 4,
    val manualReserve: Int = 1,
) {
    init {
        require(activeLimit > 0 && manualReserve in 1..activeLimit)
    }
}
