package com.jjt.ingsis.snippets.language.printscript.execution

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("language.printscript.execution")
data class ExecutionClientProperties(
    val streamTimeout: Duration = Duration.ofSeconds(DEFAULT_STREAM_SECONDS),
) {
    init {
        require(!streamTimeout.isNegative && !streamTimeout.isZero)
    }

    companion object {
        private const val DEFAULT_STREAM_SECONDS = 135L
    }
}
