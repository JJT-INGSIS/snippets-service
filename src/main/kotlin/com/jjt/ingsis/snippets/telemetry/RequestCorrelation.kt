package com.jjt.ingsis.snippets.telemetry

import com.jjt.ingsis.snippets.background.NewBackgroundJob
import com.newrelic.api.agent.NewRelic
import org.slf4j.MDC
import java.util.UUID

private const val HEX_RADIX = 16
private const val HALF = 16

fun requestCorrelationId(requestId: String?): UUID? =
    acceptedRequestId(requestId)?.let { hex ->
        UUID(hex.take(HALF).toULong(HEX_RADIX).toLong(), hex.drop(HALF).toULong(HEX_RADIX).toLong())
    }

fun currentCorrelationId(): UUID = requestCorrelationId(MDC.get(REQUEST_ID)) ?: UUID.randomUUID()

fun attributedToRequest(job: NewBackgroundJob): NewBackgroundJob {
    NewRelic.addCustomParameter(EVENT_ID, job.event.eventId.toString())
    NewRelic.addCustomParameter(JOB_ID, job.event.jobId.toString())
    NewRelic.addCustomParameter(CORRELATION_ID, job.event.correlationId.toString())
    return job
}
