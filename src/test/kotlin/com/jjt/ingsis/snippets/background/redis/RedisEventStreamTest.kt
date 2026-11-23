package com.jjt.ingsis.snippets.background.redis

import com.jjt.ingsis.snippets.background.EventStreamUnavailable
import com.jjt.ingsis.snippets.background.OutboxSettings
import com.jjt.ingsis.snippets.background.newJob
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate

class RedisEventStreamTest {
    @Test
    fun `missing append acknowledgement is reported as stream unavailable`() {
        val redis = mock(StringRedisTemplate::class.java)
        val operations = mock(StreamOperations::class.java)
        doReturn(operations).`when`(redis).opsForStream<String, String>()

        assertThatThrownBy { RedisEventStream(redis, OutboxSettings()).publish(newJob().event) }
            .isInstanceOf(EventStreamUnavailable::class.java)
            .hasCauseInstanceOf(IllegalStateException::class.java)
            .hasRootCauseMessage("Redis did not acknowledge the append")
    }
}
