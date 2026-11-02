package com.jjt.ingsis.snippets.background

import com.jjt.ingsis.snippets.background.redis.WorkerConfiguration
import com.jjt.ingsis.snippets.background.redis.WorkerSchedule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

class WorkerSchedulingTest {
    @Test
    fun `disabled worker starts without Redis and never registers a consumer schedule`() {
        ApplicationContextRunner().withUserConfiguration(WorkerConfiguration::class.java).run {
            assertThat(it).hasNotFailed()
            assertThat(it.getBeansOfType(WorkerSchedule::class.java)).isEmpty()
        }
    }

    @Test
    fun `a blocked background turn does not delay unqualified manual recovery tasks`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val manualRan = CountDownLatch(1)
        val consumer =
            Mockito.mock(JobConsumer::class.java) { invocation ->
                if (invocation.method.name == "cycle") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    null
                } else {
                    Mockito.RETURNS_DEFAULTS.answer(invocation)
                }
            }
        ApplicationContextRunner()
            .withUserConfiguration(WorkerConfiguration::class.java)
            .withPropertyValues("snippets.worker.enabled=true", "snippets.worker.poll-delay=1h")
            .withBean(JobConsumer::class.java, Supplier { consumer })
            .withBean(WorkerSettings::class.java, Supplier { WorkerSettings(enabled = true) })
            .withBean(CapacitySettings::class.java, Supplier { CapacitySettings(enabled = true) })
            .withBean(OutboxSettings::class.java, Supplier { OutboxSettings() })
            .withBean(StringRedisTemplate::class.java, Supplier { Mockito.mock(StringRedisTemplate::class.java) })
            .withBean(ManualRecoveryProbe::class.java, Supplier { ManualRecoveryProbe(entered, manualRan) })
            .run { context ->
                try {
                    assertThat(context).hasNotFailed()
                    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
                    assertThat(manualRan.await(2, TimeUnit.SECONDS)).isTrue()
                } finally {
                    release.countDown()
                }
            }
    }
}

class ManualRecoveryProbe(
    private val backgroundEntered: CountDownLatch,
    private val ran: CountDownLatch,
) {
    @Scheduled(fixedDelay = 3600000, initialDelay = 10)
    fun recover() {
        if (backgroundEntered.await(2, TimeUnit.SECONDS)) ran.countDown()
    }
}
