package com.jjt.ingsis.snippets.permissions

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

internal val snippetId: UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
internal const val OWNER = "dev-thiago"

internal fun ownershipBody(owner: String = OWNER): String = """{"snippetId":"$snippetId","ownerId":"$owner"}"""

internal fun problemBody(
    status: Int,
    category: String,
): String =
    """
    {"type":"urn:permissions:problem:$category","status":$status,
    "title":"Any title","detail":"Any wording","instance":"/ownership/$snippetId"}
    """.trimIndent()

internal data class RecordedRequest(
    val method: String,
    val path: String,
    val body: String,
    val contentType: String?,
)

internal class StubPermissionsService(
    status: Int,
    body: String,
    contentType: String = "application/json",
    delay: Duration = Duration.ZERO,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val received: MutableList<RecordedRequest> = CopyOnWriteArrayList()
    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            received.add(
                RecordedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.toString(),
                    body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8),
                    contentType = exchange.requestHeaders.getFirst("Content-Type"),
                ),
            )
            Thread.sleep(delay.toMillis())
            try {
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", contentType)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (_: java.io.IOException) {
                // A timed-out client has already closed the connection.
                exchange.close()
            }
        }
        server.start()
    }

    fun transport(readTimeout: Duration = Duration.ofSeconds(2)): PermissionsHttpTransport =
        PermissionsHttpTransport(
            PermissionsProperties(baseUrl = baseUrl, connectTimeout = Duration.ofSeconds(1), readTimeout = readTimeout),
        )

    override fun close() {
        server.stop(0)
    }
}
