package com.jjt.ingsis.snippets.telemetry

const val REQUEST_ID = "request.id"
const val OPERATION = "operation"
const val OUTCOME = "outcome"
const val DURATION_MS = "duration.ms"
const val REQUEST_ID_HEADER = "X-Request-ID"

val TELEMETRY_KEYS = listOf(REQUEST_ID, OPERATION, OUTCOME, DURATION_MS)

private val NGINX_REQUEST_ID = Regex("[a-f0-9]{32}")

fun acceptedRequestId(header: String?): String? = header?.takeIf { NGINX_REQUEST_ID.matches(it) }
