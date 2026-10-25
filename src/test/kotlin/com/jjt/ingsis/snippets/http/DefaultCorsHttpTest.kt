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
        "spring.datasource.url=jdbc:h2:mem:corsdefault;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
        "snippets.cors.allowed-origins=",
    ],
)
@ActiveProfiles("dev")
class DefaultCorsHttpTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") private val port: Int,
    ) {
        @Test
        fun `default grants no cross origin access and preserves proxied POST application errors`() {
            val api = CorsRequests(port)
            assertThat(
                api
                    .get("/languages", "http://localhost:5173")
                    .headers()
                    .firstValue("Access-Control-Allow-Origin"),
            ).isEmpty()
            assertThat(
                api
                    .preflight("/snippets", "POST", "content-type")
                    .headers()
                    .firstValue("Access-Control-Allow-Origin"),
            ).isEmpty()
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$port/snippets"))
                    .header("Origin", "http://localhost:5173")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            assertThat(response.statusCode()).isEqualTo(400)
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty()
            assertThat(response.body()).contains("urn:snippets:problem:invalid-request")
        }
    }
