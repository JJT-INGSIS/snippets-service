package com.jjt.ingsis.snippets.background

import kotlin.math.pow
import kotlin.random.Random

private const val MAX_RETRY_EXPONENT = 30
private const val MIN_RETRY_JITTER = 0.5

fun WorkerRetries.delayMillis(attempt: Int): Long {
    val factor = 2.0.pow((attempt - 1).coerceIn(0, MAX_RETRY_EXPONENT))
    val cap = minOf(maximum.toMillis().toDouble(), initial.toMillis() * factor)
    return (cap * Random.nextDouble(MIN_RETRY_JITTER, 1.0)).toLong().coerceAtLeast(1)
}

fun safeJobReason(value: String): String = value.takeIf { it.matches(Regex("[A-Z0-9_-]{1,80}")) } ?: "HANDLER_FAILURE"
