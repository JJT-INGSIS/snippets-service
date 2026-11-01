package com.jjt.ingsis.snippets.background.redis

import com.jjt.ingsis.snippets.background.EventDeliveryStore
import com.jjt.ingsis.snippets.background.OutboxPublisher
import com.jjt.ingsis.snippets.background.OutboxSettings
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "snippets.outbox", name = ["enabled"], havingValue = "true")
@EnableScheduling
class OutboxConfiguration {
    @Bean
    fun outboxPublisher(
        deliveries: EventDeliveryStore,
        redis: StringRedisTemplate,
        settings: OutboxSettings,
    ): OutboxPublisher = OutboxPublisher(deliveries, RedisEventStream(redis, settings), settings)

    @Bean
    fun outboxSchedule(publisher: OutboxPublisher): OutboxSchedule = OutboxSchedule(publisher)
}

class OutboxSchedule(
    private val publisher: OutboxPublisher,
) {
    private val logger = LoggerFactory.getLogger(OutboxSchedule::class.java)

    @Scheduled(fixedDelayString = "\${snippets.outbox.poll-delay:2s}")
    fun publish() {
        // Spring's scheduler reports unexpected failures; leases make the next cycle recoverable.
        val published = publisher.publishBatch()
        if (published > 0) logger.debug("Outbox batch published={}", published)
    }
}
