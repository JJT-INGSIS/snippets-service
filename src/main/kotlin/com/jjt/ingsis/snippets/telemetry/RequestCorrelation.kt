package com.jjt.ingsis.snippets.telemetry

import org.slf4j.MDC
import java.util.UUID

private const val HEX_RADIX = 16
private const val HALF = 16

fun requestCorrelationId(requestId: String?): UUID? =
    acceptedRequestId(requestId)?.let { hex ->
        UUID(hex.take(HALF).toULong(HEX_RADIX).toLong(), hex.drop(HALF).toULong(HEX_RADIX).toLong())
    }

fun currentCorrelationId(): UUID = requestCorrelationId(MDC.get(REQUEST_ID)) ?: UUID.randomUUID()
