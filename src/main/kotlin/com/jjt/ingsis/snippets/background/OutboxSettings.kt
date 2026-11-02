package com.jjt.ingsis.snippets.background

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

private const val DEFAULT_BATCH_SIZE = 10
private const val MAX_BATCH_SIZE = 100
private const val DEFAULT_LEASE_SECONDS = 30L
private const val DEFAULT_RETRY_SECONDS = 2L
private const val DEFAULT_MAX_RETRY_SECONDS = 300L

@ConfigurationProperties("snippets.outbox")
data class OutboxSettings(
    val enabled: Boolean = false,
    val stream: String = "snippet-searcher:local:events",
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val lease: Duration = Duration.ofSeconds(DEFAULT_LEASE_SECONDS),
    val retryDelay: Duration = Duration.ofSeconds(DEFAULT_RETRY_SECONDS),
    val maxRetryDelay: Duration = Duration.ofSeconds(DEFAULT_MAX_RETRY_SECONDS),
) {
    init {
        require(stream.isNotBlank())
        require(batchSize in 1..MAX_BATCH_SIZE)
        require(lease.toMillis() > 0)
        require(retryDelay.toMillis() > 0 && maxRetryDelay >= retryDelay)
    }
}
