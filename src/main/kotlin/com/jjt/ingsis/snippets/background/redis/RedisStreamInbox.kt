package com.jjt.ingsis.snippets.background.redis

import com.jjt.ingsis.snippets.background.OutboxSettings
import com.jjt.ingsis.snippets.background.StreamDelivery
import com.jjt.ingsis.snippets.background.StreamInbox
import com.jjt.ingsis.snippets.background.WorkerSettings
import io.lettuce.core.XAutoClaimArgs
import io.lettuce.core.api.async.RedisStreamAsyncCommands
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.RedisSystemException
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class RedisStreamInbox(
    private val redis: StringRedisTemplate,
    private val outbox: OutboxSettings,
    private val settings: WorkerSettings,
    private val consumer: String,
) : StreamInbox {
    private var cursor = "0-0"

    override fun initialize() {
        try {
            redis.execute(
                RedisCallback { connection ->
                    connection.streamCommands().xGroupCreate(
                        outbox.stream.toByteArray(),
                        settings.group,
                        ReadOffset.from("0-0"),
                        true,
                    )
                },
            )
        } catch (failure: DataAccessException) {
            if (generateSequence<Throwable>(
                    failure,
                ) { it.cause }.none { it.message?.contains("BUSYGROUP") == true }
            ) {
                throw failure
            }
        }
    }

    override fun fresh(): List<StreamDelivery> =
        redis
            .opsForStream<String, String>()
            .read(
                Consumer.from(settings.group, consumer),
                StreamReadOptions.empty().count(settings.batchSize.toLong()),
                StreamOffset.create(outbox.stream, ReadOffset.lastConsumed()),
            ).orEmpty()
            .map { StreamDelivery(outbox.stream, it.id.value, it.value["event"]) }

    override fun recover(): List<StreamDelivery> {
        if (redis.opsForStream<String, String>().pending(outbox.stream, settings.group)?.totalPendingMessages == 0L) {
            return emptyList()
        }
        return redis
            .execute(
                RedisCallback { connection ->
                    // XAUTOCLAIM is not in StreamOperations; reuse its configured Lettuce connection.
                    @Suppress("UNCHECKED_CAST")
                    val native = connection.nativeConnection as RedisStreamAsyncCommands<ByteArray, ByteArray>
                    val args =
                        XAutoClaimArgs.Builder
                            .xautoclaim(
                                io.lettuce.core.Consumer
                                    .from(settings.group.toByteArray(), consumer.toByteArray()),
                                settings.reclaimIdle,
                                cursor,
                            ).count(settings.batchSize.toLong())
                    val claimed =
                        try {
                            native.xautoclaim(outbox.stream.toByteArray(), args).get(2, TimeUnit.SECONDS)
                        } catch (failure: InterruptedException) {
                            Thread.currentThread().interrupt()
                            recoveryFailure(failure)
                        } catch (failure: ExecutionException) {
                            recoveryFailure(failure)
                        } catch (failure: TimeoutException) {
                            recoveryFailure(failure)
                        }
                    cursor = claimed.id
                    claimed.messages.map { message ->
                        val payload =
                            message.body.entries
                                .firstOrNull { it.key.toString(Charsets.UTF_8) == "event" }
                                ?.value
                                ?.toString(Charsets.UTF_8)
                        StreamDelivery(outbox.stream, message.id, payload)
                    }
                },
            ).orEmpty()
    }

    private fun recoveryFailure(cause: Exception): Nothing =
        throw RedisSystemException("Stream recovery unavailable", cause)

    override fun acknowledge(delivery: StreamDelivery) {
        redis.opsForStream<String, String>().acknowledge(delivery.stream, settings.group, delivery.id)
    }
}
