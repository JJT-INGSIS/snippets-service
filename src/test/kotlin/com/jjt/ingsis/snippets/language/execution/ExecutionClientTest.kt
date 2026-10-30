package com.jjt.ingsis.snippets.language.execution

import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.printscript.PrintScriptProperties
import com.jjt.ingsis.snippets.language.printscript.execution.ExecutionClientProperties
import com.jjt.ingsis.snippets.language.printscript.execution.PrintScriptExecutor
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ExecutionClientTest {
    private val id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `Language discovers execution without changing validation capability`() {
        server { reply(it, 201, descriptor()) }.use { server ->
            val client = client(server)
            val language = Language(emptyList(), listOf(client))
            assertThat(language.executorFor("printscript")).isInstanceOf(ExecutionResult.Success::class.java)
            assertThat(
                language.executorFor("other"),
            ).isEqualTo(ExecutionResult.Rejected("unsupported-execution-capability"))
            assertThat(
                client.start(ExecutionRequest("", "2.0", ExecutionMode.TEST)),
            ).isEqualTo(ExecutionResult.Rejected("unsupported-version"))
        }
    }

    @Test
    fun `start sends only code version mode and ordered exact inputs`() {
        server { exchange ->
            assertThat(exchange.requestMethod).isEqualTo("POST")
            val body = mapper.readTree(exchange.requestBody.readBytes())
            assertThat(body.properties().map { it.key }).containsExactlyInAnyOrder("code", "version", "mode", "inputs")
            assertThat(
                body
                    .path("inputs")
                    .asSequence()
                    .map { it.asString() }
                    .toList(),
            ).containsExactly("", "ñ", "ñ")
            reply(exchange, 201, descriptor())
        }.use { server ->
            val result =
                client(
                    server,
                ).start(
                    ExecutionRequest(
                        code = "println(1);",
                        version = "1.1",
                        mode = ExecutionMode.TEST,
                        inputs = listOf("", "ñ", "ñ"),
                    ),
                )
            assertThat((result as ExecutionResult.Success).value.id).isEqualTo(id)
        }
    }

    @Test
    fun `decodes split UTF8 and NDJSON frames preserving every event and diagnostic`() {
        server { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/x-ndjson")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                for (byte in events().toByteArray(Charsets.UTF_8)) {
                    output.write(byte.toInt())
                    output.flush()
                }
            }
        }.use { server ->
            val received = mutableListOf<ExecutionEvent>()
            assertThat(client(server).events(id, 0, received::add)).isInstanceOf(ExecutionResult.Success::class.java)
            assertThat(received.map { it.seq }).containsExactly(1L, 2L, 3L)
            assertThat((received[1].payload as ExecutionPayload.Output).text).isEqualTo("ñ\n")
            val terminal = received.last().payload as ExecutionPayload.Terminal
            assertThat(terminal.diagnostics.map { it.rule }).containsExactly("EXAMPLE_ERROR", "SECOND_ERROR")
        }
    }

    @Test
    fun `rejects truncated malformed and mismatched event streams`() {
        for (body in listOf(
            events().trimEnd(),
            events().replace("\"seq\":2", "\"seq\":5"),
            events() + "\n",
            events().replace(id.toString(), UUID.randomUUID().toString()),
        )) {
            val fixture =
                server { reply(exchange = it, status = 200, body = body, contentType = "application/x-ndjson") }
            fixture.use { server ->
                assertThat(client(server).events(id, 0) {}).isInstanceOf(ExecutionResult.Failed::class.java)
            }
        }
        server { reply(exchange = it, status = 200, body = events(), contentType = "application/json") }.use { server ->
            assertThat(client(server).events(id, 0) {}).isInstanceOf(ExecutionResult.Failed::class.java)
        }
    }

    @Test
    fun `EOF with partial output is unknown not successful completion`() {
        server {
            reply(
                exchange = it,
                status = 200,
                body = events().lineSequence().take(2).joinToString("\n", postfix = "\n"),
                contentType = "application/x-ndjson",
            )
        }.use { server ->
            val received = mutableListOf<ExecutionEvent>()
            assertThat(
                client(server).events(id, 0, received::add),
            ).isEqualTo(ExecutionResult.Failed("execution-stream-interrupted", true))
            assertThat(received).hasSize(2)
        }
    }

    @Test
    fun `lost start acknowledgment is uncertain and does not retry POST`() {
        val calls = AtomicInteger()
        server { exchange ->
            calls.incrementAndGet()
            exchange.close()
        }.use { server ->
            val result = client(server).start(ExecutionRequest("", "1.0", ExecutionMode.TEST))
            assertThat(result).isEqualTo(ExecutionResult.Failed("execution-service-unavailable", true))
            assertThat(calls.get()).isEqualTo(1)
        }
    }

    @Test
    fun `a start that cannot even connect is a certain failure`() {
        val port = ServerSocket(0).use { it.localPort }
        val closed =
            PrintScriptExecutor(
                PrintScriptProperties(baseUrl = "http://127.0.0.1:$port", readTimeout = Duration.ofMillis(500)),
                ExecutionClientProperties(Duration.ofMillis(250)),
                mapper,
            )
        val result = closed.start(ExecutionRequest("", "1.0", ExecutionMode.TEST))
        assertThat(result).isEqualTo(ExecutionResult.Failed("execution-service-unavailable", false))
    }

    @Test
    fun `uses stable problem type rather than human messages and rejects invalid acknowledgments`() {
        server {
            reply(
                exchange = it,
                status = 409,
                body = """{"type":"urn:printscript:problem:input-conflict","status":409,"detail":"wording changes"}""",
                contentType = "application/problem+json",
            )
        }.use { server ->
            assertThat(
                client(server).input(id, UUID.randomUUID(), ""),
            ).isEqualTo(ExecutionResult.Rejected("input-conflict"))
        }
        server { reply(it, 200, """{"requestId":"$id","accepted":false}""") }.use { server ->
            assertThat(client(server).input(id, id, "")).isInstanceOf(ExecutionResult.Failed::class.java)
        }
    }

    @Test
    fun `body timeout closes an otherwise endless stream`() {
        server { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/x-ndjson")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write(' '.code)
            exchange.responseBody.flush()
            Thread.sleep(1000)
            exchange.close()
        }.use { server ->
            val start = System.nanoTime()
            assertThat(client(server).events(id, 0) {}).isInstanceOf(ExecutionResult.Failed::class.java)
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1))
        }
    }

    @Test
    fun `opening preserves a cursor rejection before exposing a readable channel`() {
        server {
            reply(
                exchange = it,
                status = 409,
                body = """{"type":"urn:printscript:problem:event-history-unavailable","status":409}""",
                contentType = "application/problem+json",
            )
        }.use { server ->
            assertThat(client(server).openEvents(id, 0))
                .isEqualTo(ExecutionResult.Rejected("event-history-unavailable"))
        }
    }

    @Test
    fun `an opened channel has a deadline even when its caller never begins consuming`() {
        val release = java.util.concurrent.CountDownLatch(1)
        val providerStillOpen =
            java.util.concurrent.atomic
                .AtomicBoolean(true)
        server { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/x-ndjson")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write(' '.code)
            exchange.responseBody.flush()
            release.await(2, java.util.concurrent.TimeUnit.SECONDS)
            providerStillOpen.set(false)
            exchange.close()
        }.use { server ->
            val channel = (client(server).openEvents(id, 0) as ExecutionResult.Success).value
            try {
                Thread.sleep(350)
                assertThat(release.count).isEqualTo(1)
                assertThat(channel.read {}).isInstanceOf(ExecutionResult.Failed::class.java)
                assertThat(providerStillOpen.get()).isTrue()
                assertThat(channel.read {}).isEqualTo(ExecutionResult.Rejected("execution-consumer-conflict"))
            } finally {
                channel.close()
                release.countDown()
            }
        }
    }

    private fun client(server: TestExecutionServer): PrintScriptExecutor =
        PrintScriptExecutor(
            PrintScriptProperties(baseUrl = server.url, readTimeout = Duration.ofMillis(500)),
            ExecutionClientProperties(Duration.ofMillis(250)),
            mapper,
        )

    private fun descriptor(): String =
        """{"executionId":"$id","mode":"TEST","state":"RUNNING","lastSeq":1,"limits":{"activeGlobal":4,"totalSeconds":120,"inputWaitSeconds":30,"reconnectSeconds":15,"codeBytes":1048576,"inputBytes":8192,"outputBytes":1048576,"bufferEvents":256,"bufferBytes":262144,"terminalRetentionSeconds":60}}"""

    private fun events(): String =
        """{"executionId":"$id","seq":1,"type":"started","data":{"mode":"TEST"}}
{"executionId":"$id","seq":2,"type":"output","data":{"text":"ñ\n"}}
{"executionId":"$id","seq":3,"type":"terminal","data":{"outcome":"PROGRAM_ERROR","reason":"program-error","diagnostics":[{"rule":"EXAMPLE_ERROR","message":"message","line":2,"column":1},{"rule":"SECOND_ERROR","message":"another","line":3,"column":4}]}}
"""

    private fun server(answer: (HttpExchange) -> Unit): TestExecutionServer = TestExecutionServer(answer)

    private fun reply(
        exchange: HttpExchange,
        status: Int,
        body: String,
        contentType: String = "application/json",
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

private class TestExecutionServer(
    answer: (HttpExchange) -> Unit,
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = executor
        server.createContext("/", answer)
        server.start()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
