package com.jjt.ingsis.snippets.telemetry.background

import com.jjt.ingsis.snippets.background.OutboxSettings
import com.jjt.ingsis.snippets.background.WorkerSettings
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "snippets.telemetry.background", name = ["enabled"], havingValue = "true")
@EnableScheduling
class BackgroundSamplerConfiguration {
    @Bean(defaultCandidate = false)
    fun backgroundTelemetryScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("background-telemetry-")
        }

    @Bean
    fun backgroundSampler(
        dataSource: DataSource,
        redis: StringRedisTemplate,
        outbox: OutboxSettings,
        worker: WorkerSettings,
        settings: BackgroundSamplerSettings,
    ): BackgroundSampler {
        val jdbc = JdbcTemplate(dataSource).apply { queryTimeout = settings.queryTimeout.toSeconds().toInt() }
        return BackgroundSampler(
            database = DatabaseBacklog(jdbc, settings),
            stream = StreamBacklog(redis = redis, stream = outbox.stream, group = worker.group, settings = settings),
            sink = NewRelicSampleSink(),
            settings = settings,
        )
    }

    @Bean
    fun backgroundSampleSchedule(sampler: BackgroundSampler): BackgroundSampleSchedule =
        BackgroundSampleSchedule(sampler)
}

class BackgroundSampleSchedule(
    private val sampler: BackgroundSampler,
) {
    @Scheduled(
        initialDelayString = "\${snippets.telemetry.background.interval:60s}",
        fixedDelayString = "\${snippets.telemetry.background.interval:60s}",
        scheduler = "backgroundTelemetryScheduler",
    )
    fun sample() = sampler.sample()
}
