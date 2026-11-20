package com.jjt.ingsis.snippets.telemetry.background

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

private const val DEFAULT_FINISHED_WINDOW_MINUTES = 15L
private const val DEFAULT_QUERY_TIMEOUT_SECONDS = 2L
private const val MAX_PENDING_INSPECTED = 100L

@ConfigurationProperties("snippets.telemetry.background")
data class BackgroundSamplerSettings(
    val enabled: Boolean = false,
    val environment: String = "local",
    val finishedWindow: Duration = Duration.ofMinutes(DEFAULT_FINISHED_WINDOW_MINUTES),
    val queryTimeout: Duration = Duration.ofSeconds(DEFAULT_QUERY_TIMEOUT_SECONDS),
    val pendingInspected: Long = MAX_PENDING_INSPECTED,
) {
    init {
        require(environment.isNotBlank())
        require(!finishedWindow.isNegative && !finishedWindow.isZero)
        require(!queryTimeout.isNegative && !queryTimeout.isZero)
        require(pendingInspected in 1..MAX_PENDING_INSPECTED)
    }
}
