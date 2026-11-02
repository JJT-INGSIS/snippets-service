package com.jjt.ingsis.snippets.background.redis

import com.jjt.ingsis.snippets.background.DomainEvent
import com.jjt.ingsis.snippets.background.EventStream
import com.jjt.ingsis.snippets.background.EventStreamUnavailable
import com.jjt.ingsis.snippets.background.OutboxSettings
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.json.JsonMapper

class RedisEventStream(
    private val redis: StringRedisTemplate,
    private val settings: OutboxSettings,
) : EventStream {
    private val json = JsonMapper.builder().findAndAddModules().build()

    override fun publish(event: DomainEvent): String {
        val record =
            StreamRecords
                .string(
                    mapOf("event" to json.writeValueAsString(event)),
                ).withStreamKey(settings.stream)
        return try {
            val id = redis.opsForStream<String, String>().add(record)
            checkNotNull(id) { "Redis did not acknowledge the append" }.value
        } catch (failure: DataAccessException) {
            throw EventStreamUnavailable(failure)
        }
    }
}
