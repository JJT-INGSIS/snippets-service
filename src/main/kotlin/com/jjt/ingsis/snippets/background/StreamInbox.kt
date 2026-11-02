package com.jjt.ingsis.snippets.background

data class StreamDelivery(
    val stream: String,
    val id: String,
    val payload: String?,
)

interface StreamInbox {
    fun initialize()

    fun fresh(): List<StreamDelivery>

    fun recover(): List<StreamDelivery>

    fun acknowledge(delivery: StreamDelivery)
}

fun interface RejectedDeliveries {
    fun persist(
        delivery: StreamDelivery,
        reason: String,
    )
}
