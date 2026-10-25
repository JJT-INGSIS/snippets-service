package com.jjt.ingsis.snippets.http

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:cors;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
        "snippets.cors.allowed-origins=http://localhost:5173,http://127.0.0.1:5173",
    ],
)
@ActiveProfiles("dev")
class CorsHttpTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") private val port: Int,
    ) {
        private val api by lazy { CorsRequests(port) }

        @Test
        fun `preflight permits real operations without requiring identity`() {
            listOf(
                Triple("/snippets", "POST", "content-type,idempotency-key,x-dev-actor-id"),
                Triple("/snippets/123", "PUT", "content-type,x-dev-actor-id"),
                Triple("/snippets/123/shares/recipient", "PUT", "x-dev-actor-id"),
                Triple("/languages", "GET", ""),
                Triple("/users", "GET", "x-dev-actor-id"),
            ).forEach { (path, method, headers) ->
                val response = api.preflight(path, method, headers)
                assertThat(response.statusCode()).isEqualTo(200)
                assertThat(response.headers().allValues("Access-Control-Allow-Origin")).containsExactly(LOCAL_ORIGIN)
                assertThat(response.headers().firstValue("Access-Control-Allow-Methods").orElse("")).contains(method)
                assertThat(response.headers().firstValue("Access-Control-Max-Age").orElse("")).isEqualTo("300")
                assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty()
                if (headers.isNotEmpty()) {
                    assertThat(
                        response
                            .headers()
                            .firstValue("Access-Control-Allow-Headers")
                            .orElse("")
                            .lowercase(),
                    ).contains(*headers.split(',').toTypedArray())
                }
            }
        }

        @Test
        fun `disallowed origins methods headers and routes are rejected`() {
            listOf(
                api.preflight(
                    path = "/snippets",
                    method = "POST",
                    headers = "content-type",
                    origin = "https://untrusted.example",
                ),
                api.preflight(
                    path = "/snippets",
                    method = "POST",
                    headers = "content-type",
                    origin = "http://localhost:5174",
                ),
                api.preflight(path = "/snippets", method = "POST", headers = "content-type", origin = "null"),
                api.preflight("/snippets", "DELETE", ""),
                api.preflight("/users", "POST", "content-type"),
                api.preflight("/snippets", "POST", "authorization"),
            ).forEach { response ->
                assertThat(
                    response.statusCode(),
                ).describedAs(response.request().toString() + response.request().headers()).isEqualTo(403)
                assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty()
            }
            assertThat(
                api.preflight("/internal", "GET", "").headers().firstValue("Access-Control-Allow-Origin"),
            ).isEmpty()
        }

        @Test
        fun `actual success and errors retain CORS while identity still applies`() {
            val success = api.get("/languages", LOCAL_ORIGIN)
            assertThat(success.statusCode()).isEqualTo(200)
            assertThat(success.headers().allValues("Access-Control-Allow-Origin")).containsExactly(LOCAL_ORIGIN)
            assertThat(success.headers().allValues("Vary").joinToString(",")).contains("Origin")
            assertThat(success.headers().firstValue("Access-Control-Expose-Headers")).isEmpty()
            val missingIdentity = api.get("/users", LOCAL_ORIGIN)
            assertThat(missingIdentity.statusCode()).isEqualTo(401)
            assertThat(missingIdentity.headers().allValues("Access-Control-Allow-Origin")).containsExactly(LOCAL_ORIGIN)
            assertThat(api.get("/users", LOCAL_ORIGIN, "dev-thiago").statusCode()).isEqualTo(200)
            val internal = api.get("/languages")
            assertThat(internal.statusCode()).isEqualTo(200)
            assertThat(internal.headers().firstValue("Access-Control-Allow-Origin")).isEmpty()
            assertThat(api.get("/languages", "https://untrusted.example").statusCode()).isEqualTo(403)
        }
    }

private const val LOCAL_ORIGIN = "http://localhost:5173"

internal class CorsRequests(
    port: Int,
) {
    private val base = "http://127.0.0.1:$port"
    private val client = HttpClient.newHttpClient()

    fun preflight(
        path: String,
        method: String,
        headers: String,
        origin: String = LOCAL_ORIGIN,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create(base + path))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", method)
        if (headers.isNotEmpty()) request.header("Access-Control-Request-Headers", headers)
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun get(
        path: String,
        origin: String? = null,
        actor: String? = null,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(base + path)).GET()
        if (origin != null) request.header("Origin", origin)
        if (actor != null) request.header("X-Dev-Actor-Id", actor)
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
