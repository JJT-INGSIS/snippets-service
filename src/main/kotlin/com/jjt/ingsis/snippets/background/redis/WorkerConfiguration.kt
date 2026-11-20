package com.jjt.ingsis.snippets.background.redis

import com.jjt.ingsis.snippets.background.CapacitySettings
import com.jjt.ingsis.snippets.background.JobConsumer
import com.jjt.ingsis.snippets.background.OutboxSettings
import com.jjt.ingsis.snippets.background.WorkerSettings
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.UUID

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "snippets.worker", name = ["enabled"], havingValue = "true")
class WorkerConfiguration {
    // With two schedulers Spring routes unqualified maintenance tasks to this conventional bean name.
    @Bean
    fun taskScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("scheduled-maintenance-")
            setWaitForTasksToCompleteOnShutdown(false)
        }

    @Bean
    fun backgroundScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("background-consumer-")
            setWaitForTasksToCompleteOnShutdown(false)
        }

    @Bean
    fun workerSchedule(
        redis: StringRedisTemplate,
        consumer: JobConsumer,
        settings: WorkerSettings,
        outbox: OutboxSettings,
        capacity: CapacitySettings,
    ): WorkerSchedule {
        check(capacity.enabled) { "Enable durable capacity before activating consumers" }
        return WorkerSchedule(
            consumer,
            RedisStreamInbox(
                redis = redis,
                outbox = outbox,
                settings = settings,
                consumer = UUID.randomUUID().toString(),
            ),
        )
    }
}

class WorkerSchedule(
    private val consumer: JobConsumer,
    private val inbox: RedisStreamInbox,
) {
    @Scheduled(fixedDelayString = "\${snippets.worker.poll-delay:1s}", scheduler = "backgroundScheduler")
    fun cycle() {
        // Spring reports failures and retries the next cycle; no ACK is fabricated.
        consumer.cycle(inbox)
    }
}
