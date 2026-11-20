package com.jjt.ingsis.snippets.language.execution

import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.printscript.PrintScriptProperties
import com.jjt.ingsis.snippets.language.printscript.execution.ExecutionClientProperties
import com.jjt.ingsis.snippets.language.printscript.execution.PrintScriptExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@EnabledIfEnvironmentVariable(named = "PRINTSCRIPT_EXECUTION_URL", matches = ".+")
class PrintScriptExecutionLiveTest {
    private val client =
        PrintScriptExecutor(
            PrintScriptProperties(baseUrl = checkNotNull(System.getenv("PRINTSCRIPT_EXECUTION_URL"))),
            ExecutionClientProperties(),
            JsonMapper.builder().findAndAddModules().build(),
        )

    @Test
    fun `Language talks to real PrintScript streaming output before completing interactive input`() {
        val language = Language(emptyList(), listOf(client))
        val adapter = (language.executorFor("printscript") as ExecutionResult.Success).value
        val command =
            ExecutionRequest(
                "println(\"antes\"); let a: string = readInput(\"?\"); println(a);",
                "1.1",
                ExecutionMode.INTERACTIVE,
            )
        val descriptor = (adapter.start(command) as ExecutionResult.Success).value
        val events = CopyOnWriteArrayList<ExecutionEvent>()
        val requested = CountDownLatch(1)
        val requestId = AtomicReference<java.util.UUID>()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val stream =
                reader.submit<ExecutionResult<Unit>> {
                    adapter.events(descriptor.id, 0) { event ->
                        events.add(event)
                        (event.payload as? ExecutionPayload.InputRequired)?.let {
                            requestId.set(it.requestId)
                            requested.countDown()
                        }
                    }
                }
            assertThat(requested.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(stream.isDone).isFalse()
            assertThat(events.mapNotNull { (it.payload as? ExecutionPayload.Output)?.text }).containsExactly("antes")
            assertThat(
                adapter.input(descriptor.id, requestId.get(), "ñ"),
            ).isInstanceOf(ExecutionResult.Success::class.java)
            assertThat(stream.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionResult.Success::class.java)
            assertThat(
                events.mapNotNull { (it.payload as? ExecutionPayload.Output)?.text },
            ).containsExactly("antes", "ñ")
            assertThat((events.last().payload as ExecutionPayload.Terminal).outcome).isEqualTo(ExecutionState.COMPLETED)
            assertThat(
                (adapter.describe(descriptor.id) as ExecutionResult.Success).value.state,
            ).isEqualTo(ExecutionState.COMPLETED)
        } finally {
            adapter.cancel(descriptor.id)
            reader.shutdownNow()
        }
    }

    @Test
    fun `real test inputs exhaustion runtime errors and cancellation preserve distinct outcomes`() {
        val cases =
            listOf(
                ExecutionRequest("println(1);", "1.0", ExecutionMode.TEST) to ExecutionState.COMPLETED,
                ExecutionRequest("let a: string = readInput(\"?\");", "1.1", ExecutionMode.TEST) to
                    ExecutionState.INPUT_EXHAUSTED,
                ExecutionRequest("println(1 / 0);", "1.0", ExecutionMode.TEST) to ExecutionState.PROGRAM_ERROR,
            )
        for ((request, expected) in cases) {
            val descriptor = (client.start(request) as ExecutionResult.Success).value
            val events = mutableListOf<ExecutionEvent>()
            assertThat(client.events(descriptor.id, 0, events::add)).isInstanceOf(ExecutionResult.Success::class.java)
            assertThat((events.last().payload as ExecutionPayload.Terminal).outcome).isEqualTo(expected)
        }
        val descriptor =
            (
                client.start(
                    ExecutionRequest("let a: string = readInput(\"?\");", "1.1", ExecutionMode.INTERACTIVE),
                ) as ExecutionResult.Success
            ).value
        assertThat(client.cancel(descriptor.id)).isInstanceOf(ExecutionResult.Success::class.java)
        val events = mutableListOf<ExecutionEvent>()
        assertThat(client.events(descriptor.id, 0, events::add)).isInstanceOf(ExecutionResult.Success::class.java)
        assertThat((events.last().payload as ExecutionPayload.Terminal).outcome).isEqualTo(ExecutionState.CANCELLED)
    }
}
