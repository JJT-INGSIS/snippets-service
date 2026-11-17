package com.jjt.ingsis.snippets.telemetry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val WAIT_SECONDS = 5L

class MdcTaskDecoratorTest {
    @Test
    fun `work on another thread keeps the request context and leaves the thread clean`() {
        val executor = Executors.newSingleThreadExecutor()
        var seen: String? = null
        var leftover: String? = "unset"
        MDC.put(REQUEST_ID, "0123456789abcdef0123456789abcdef")
        val decorated = MdcTaskDecorator().decorate { seen = MDC.get(REQUEST_ID) }
        MDC.clear()

        executor.submit(decorated).get(WAIT_SECONDS, TimeUnit.SECONDS)
        executor.submit { leftover = MDC.get(REQUEST_ID) }.get(WAIT_SECONDS, TimeUnit.SECONDS)
        executor.shutdown()

        assertThat(seen).isEqualTo("0123456789abcdef0123456789abcdef")
        assertThat(leftover).isNull()
    }
}
