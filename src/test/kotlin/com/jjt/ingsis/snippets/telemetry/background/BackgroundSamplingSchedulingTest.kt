package com.jjt.ingsis.snippets.telemetry.background

import com.jjt.ingsis.snippets.background.CapacitySettings
import com.jjt.ingsis.snippets.background.EventDeliveryStore
import com.jjt.ingsis.snippets.background.JobConsumer
import com.jjt.ingsis.snippets.background.OutboxSettings
import com.jjt.ingsis.snippets.background.WorkerSettings
import com.jjt.ingsis.snippets.background.redis.OutboxConfiguration
import com.jjt.ingsis.snippets.background.redis.WorkerConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import javax.sql.DataSource

class BackgroundSamplingSchedulingTest {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `outbox keeps running while telemetry is blocked`(workerEnabled: Boolean) {
        val sampling = CountDownLatch(1)
        val releaseSample = CountDownLatch(1)
        val outboxRan = CountDownLatch(1)
        val sampler = Mockito.mock(BackgroundSampler::class.java)
        Mockito
            .doAnswer {
                sampling.countDown()
                check(releaseSample.await(15, TimeUnit.SECONDS))
                null
            }.`when`(sampler)
            .sample()
        val deliveries = Mockito.mock(EventDeliveryStore::class.java)
        Mockito
            .`when`(deliveries.claim())
            .thenAnswer {
                if (sampling.count == 0L) outboxRan.countDown()
                null
            }

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration::class.java))
            .withUserConfiguration(
                BackgroundSamplerConfiguration::class.java,
                OutboxConfiguration::class.java,
                WorkerConfiguration::class.java,
                SchedulingTestSettings::class.java,
            ).withPropertyValues(
                "snippets.telemetry.background.enabled=true",
                "snippets.telemetry.background.interval=10ms",
                "snippets.outbox.enabled=true",
                "snippets.outbox.poll-delay=10ms",
                "snippets.worker.enabled=$workerEnabled",
                "snippets.worker.poll-delay=1h",
                "snippets.capacity.enabled=true",
            ).withBean("blockingSampler", BackgroundSampler::class.java, Supplier { sampler }, { it.isPrimary = true })
            .withBean(DataSource::class.java, Supplier { Mockito.mock(DataSource::class.java) })
            .withBean(StringRedisTemplate::class.java, Supplier { Mockito.mock(StringRedisTemplate::class.java) })
            .withBean(EventDeliveryStore::class.java, Supplier { deliveries })
            .withBean(JobConsumer::class.java, Supplier { Mockito.mock(JobConsumer::class.java) })
            .run { context ->
                try {
                    assertThat(context).hasNotFailed()
                    assertThat(sampling.await(5, TimeUnit.SECONDS)).isTrue()
                    assertThat(outboxRan.await(5, TimeUnit.SECONDS)).isTrue()
                } finally {
                    releaseSample.countDown()
                }
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(
        BackgroundSamplerSettings::class,
        OutboxSettings::class,
        WorkerSettings::class,
        CapacitySettings::class,
    )
    class SchedulingTestSettings
}
