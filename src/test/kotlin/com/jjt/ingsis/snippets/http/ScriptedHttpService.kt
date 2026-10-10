package com.jjt.ingsis.snippets.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

internal data class StubRequest(
    val method: String,
    val path: String,
    val body: String,
)

internal data class StubResponse(
    val status: Int,
    val body: String,
    val contentType: String = "application/json",
    val delay: Duration = Duration.ZERO,
)

internal class ScriptedHttpService(
    private val usual: (StubRequest) -> StubResponse,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val threads = Executors.newCachedThreadPool()
    private val script = AtomicReference(usual)

    val received: MutableList<StubRequest> = CopyOnWriteArrayList()

    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = threads
        server.createContext("/") { exchange -> answer(exchange) }
        server.start()
    }

    fun answerWith(responder: (StubRequest) -> StubResponse) {
        script.set(responder)
    }

    fun reset() {
        script.set(usual)
        received.clear()
    }

    override fun close() {
        server.stop(0)
        threads.shutdownNow()
    }

    private fun answer(exchange: HttpExchange) {
        val request =
            StubRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                body = exchange.requestBody.readBytes().toString(Charsets.UTF_8),
            )
        received.add(request)
        val response = script.get().invoke(request)

        try {
            Thread.sleep(response.delay.toMillis())
            val bytes = response.body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", response.contentType)
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.use { output -> output.write(bytes) }
        } catch (_: IOException) {
            exchange.close()
        } catch (_: InterruptedException) {
            exchange.close()
        }
    }
}
