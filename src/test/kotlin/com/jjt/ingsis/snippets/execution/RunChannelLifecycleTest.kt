package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.language.execution.ExecutionDescriptor
import com.jjt.ingsis.snippets.language.execution.ExecutionEvent
import com.jjt.ingsis.snippets.language.execution.ExecutionMode
import com.jjt.ingsis.snippets.language.execution.ExecutionRequest
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.language.execution.LanguageEventStream
import com.jjt.ingsis.snippets.language.execution.LanguageExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class RunChannelLifecycleTest {
    private val provider = ControlledExecutor()
    private val request =
        ExecutionRequest(
            code = "println(1);",
            version = "1.1",
            mode = ExecutionMode.TEST,
            inputs = emptyList(),
        )
    private val handle = (provider.start(request) as ExecutionResult.Success).value
    private val run =
        AuditedRun(
            id = UUID.randomUUID(),
            actorId = "actor",
            snapshot = snapshot(),
            startedAt = Instant.now(),
            remote = handle,
        )

    @Test
    fun `an unexpected adapter opening error cannot leave the consumer locked`() {
        val first = AtomicBoolean(true)
        val adapter =
            object : LanguageExecutor by provider {
                override fun openEvents(
                    id: UUID,
                    after: Long,
                ): ExecutionResult<LanguageEventStream> {
                    require(!first.compareAndSet(true, false)) { "Invalid transport configuration" }
                    return provider.openEvents(id, after)
                }
            }
        val channel = RunChannel(run, adapter, mock(ExecutionAudit::class.java))
        assertThrows(IllegalArgumentException::class.java) { channel.open(0) }
        assertThat(channel.connected()).isFalse()
        val opened = channel.open(0) as RunResult.Success
        opened.value.close()
        assertThat(channel.connected()).isFalse()
    }

    @Test
    fun `a failing delegate close still releases the consumer lease`() {
        val adapter =
            object : LanguageExecutor by provider {
                override fun openEvents(
                    id: UUID,
                    after: Long,
                ): ExecutionResult<LanguageEventStream> = ExecutionResult.Success(FailingCloseStream())
            }
        val channel = RunChannel(run, adapter, mock(ExecutionAudit::class.java))
        val opened = channel.open(0) as RunResult.Success
        assertThrows(IOException::class.java) { opened.value.close() }
        assertThat(channel.connected()).isFalse()
    }
}

private class FailingCloseStream : LanguageEventStream {
    override fun read(consume: (ExecutionEvent) -> Unit): ExecutionResult<Unit> = ExecutionResult.Success(Unit)

    override fun close(): Unit = throw IOException("Connection already failed")
}
