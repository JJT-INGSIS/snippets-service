package com.jjt.ingsis.snippets.users.auth0

import com.sun.net.httpserver.HttpServer
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

internal data class DirectoryRequest(
    val path: String,
    val query: Map<String, String>,
    val body: String,
    val authorization: String?,
)

internal data class DirectoryResponse(
    val status: Int,
    val body: String,
    val type: String = "application/json",
    val location: String? = null,
)

internal class ManagementFixture : AutoCloseable {
    val requests = CopyOnWriteArrayList<DirectoryRequest>()
    val json: JsonMapper = JsonMapper.builder().build()
    var tokenResponse =
        DirectoryResponse(
            200,
            """{"access_token":"management-token","token_type":"Bearer","expires_in":600,"scope":"read:users"}""",
        )
    var usersResponse =
        DirectoryResponse(200, """{"start":0,"total":1,"users":[{"user_id":"auth0|bob","name":"Bob"}]}""")
    var memberResponse = DirectoryResponse(200, """{"user_id":"auth0|bob"}""")
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val query =
                    exchange.requestURI.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate {
                        val parts = it.split('=', limit = 2)
                        URLDecoder.decode(parts[0], Charsets.UTF_8) to
                            URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8)
                    }
                val path = exchange.requestURI.path
                requests.add(
                    DirectoryRequest(
                        path = path,
                        query = query,
                        body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8),
                        authorization = exchange.requestHeaders.getFirst("Authorization"),
                    ),
                )
                val answer =
                    when (path) {
                        "/oauth/token" -> tokenResponse
                        "/api/v2/users" -> usersResponse
                        else -> memberResponse
                    }
                exchange.responseHeaders.add("Content-Type", answer.type)
                answer.location?.let { exchange.responseHeaders.add("Location", it) }
                val body = answer.body.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(answer.status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    val transport =
        ManagementTransport(
            client =
                RestClient
                    .builder()
                    .baseUrl("http://127.0.0.1:${server.address.port}")
                    .requestFactory(
                        JdkClientHttpRequestFactory(
                            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                        ).apply {
                            setReadTimeout(Duration.ofSeconds(1))
                        },
                    ).build(),
            credential = ManagementCredential("directory-client", "fixture-secret"),
            audience = "https://tenant.fixture/api/v2/",
        )

    fun directory(clock: Clock = Clock.systemUTC()): Auth0UserDirectory =
        Auth0UserDirectory(transport, ManagementTokens(transport, clock))

    override fun close() {
        server.stop(0)
    }
}
