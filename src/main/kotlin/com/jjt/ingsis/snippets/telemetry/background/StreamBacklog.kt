package com.jjt.ingsis.snippets.telemetry.background

import io.lettuce.core.api.async.RedisStreamAsyncCommands
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class StreamGroupBacklog(
    val streamExists: Boolean,
    val groupExists: Boolean,
    val lag: Long?,
    val entriesRead: Long?,
    val pending: Long,
    val oldestPendingIdleSeconds: Long,
    val consumers: Long,
) {
    companion object {
        fun withoutGroup(streamExists: Boolean): StreamGroupBacklog =
            StreamGroupBacklog(
                streamExists = streamExists,
                groupExists = false,
                lag = null,
                entriesRead = null,
                pending = 0,
                oldestPendingIdleSeconds = 0,
                consumers = 0,
            )
    }
}

class StreamBacklog(
    private val redis: StringRedisTemplate,
    private val stream: String,
    private val group: String,
    private val settings: BackgroundSamplerSettings,
) {
    fun read(): StreamGroupBacklog {
        if (redis.hasKey(stream) != true) return StreamGroupBacklog.withoutGroup(streamExists = false)
        val info = groups()[group] ?: return StreamGroupBacklog.withoutGroup(streamExists = true)
        return StreamGroupBacklog(
            streamExists = true,
            groupExists = true,
            lag = info["lag"]?.toString()?.toLongOrNull(),
            entriesRead = info["entries-read"]?.toString()?.toLongOrNull(),
            pending = info["pending"]?.toString()?.toLongOrNull() ?: 0,
            oldestPendingIdleSeconds = oldestIdleSeconds(),
            consumers = info["consumers"]?.toString()?.toLongOrNull() ?: 0,
        )
    }

    private fun oldestIdleSeconds(): Long =
        redis
            .opsForStream<String, String>()
            .pending(stream, group, Range.unbounded<String>(), settings.pendingInspected)
            .maxOfOrNull { it.elapsedTimeSinceLastDelivery.seconds } ?: 0

    private fun groups(): Map<String, Map<String, Any?>> =
        redis
            .execute(
                RedisCallback { connection ->
                    @Suppress("UNCHECKED_CAST")
                    val native = connection.nativeConnection as RedisStreamAsyncCommands<ByteArray, ByteArray>
                    val raw =
                        try {
                            native
                                .xinfoGroups(
                                    stream.toByteArray(),
                                ).get(settings.queryTimeout.toMillis(), TimeUnit.MILLISECONDS)
                        } catch (failure: ExecutionException) {
                            throw IllegalStateException("XINFO GROUPS failed", failure)
                        } catch (failure: TimeoutException) {
                            throw IllegalStateException("XINFO GROUPS timed out", failure)
                        } catch (failure: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw IllegalStateException("XINFO GROUPS interrupted", failure)
                        }
                    raw.associate { entry -> fields(entry).let { (it["name"] as? String).orEmpty() to it } }
                },
            ).orEmpty()

    private fun fields(entry: Any?): Map<String, Any?> =
        (entry as List<*>)
            .chunked(2)
            .associate { (key, value) -> (decoded(key) as? String).orEmpty() to decoded(value) }

    private fun decoded(value: Any?): Any? = if (value is ByteArray) value.toString(Charsets.UTF_8) else value
}
