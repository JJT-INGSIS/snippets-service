package com.jjt.ingsis.snippets.telemetry

import com.jjt.ingsis.snippets.background.DomainEvent
import com.newrelic.api.agent.NewRelic
import org.slf4j.MDC

const val CORRELATION_ID = "correlation.id"
const val EVENT_ID = "event.id"
const val JOB_ID = "job.id"
const val ATTEMPT = "attempt"

class BackgroundContext private constructor(
    private val values: Map<String, String>,
) : AutoCloseable {
    init {
        values.forEach { (key, value) ->
            MDC.put(key, value)
            NewRelic.addCustomParameter(key, value)
        }
    }

    override fun close() = values.keys.forEach(MDC::remove)

    companion object {
        fun open(
            event: DomainEvent,
            attempt: Int,
        ): BackgroundContext =
            BackgroundContext(
                mapOf(
                    CORRELATION_ID to event.correlationId.toString(),
                    EVENT_ID to event.eventId.toString(),
                    JOB_ID to event.jobId.toString(),
                    ATTEMPT to attempt.toString(),
                ),
            )
    }
}
