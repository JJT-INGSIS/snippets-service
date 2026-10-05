package com.jjt.ingsis.snippets.language.printscript

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

data class ReceivedRequest(
    val method: String,
    val path: String,
    val contentType: String?,
    val body: String,
)

class StubLanguageService(
    private val status: Int,
    private val body: String,
    private val delay: Duration = Duration.ZERO,
) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val received: MutableList<ReceivedRequest> = CopyOnWriteArrayList()

    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange -> answer(exchange) }
        server.start()
    }

    override fun close() {
        server.stop(0)
    }

    private fun answer(exchange: HttpExchange) {
        received.add(
            ReceivedRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                contentType = exchange.requestHeaders.getFirst("Content-Type"),
                body = exchange.requestBody.readBytes().toString(Charsets.UTF_8),
            ),
        )
        Thread.sleep(delay.toMillis())

        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output -> output.write(bytes) }
    }
}
