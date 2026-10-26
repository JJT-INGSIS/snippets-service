package com.jjt.ingsis.snippets.http

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:corsprod;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
        "snippets.cors.allowed-origins=http://localhost:5173",
    ],
)
@ActiveProfiles("prod")
class ProductionCorsTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") private val port: Int,
    ) {
        @Test
        fun `production does not authorize development headers or trust development identity`() {
            val api = CorsRequests(port)
            val preflight = api.preflight("/users", "GET", "x-dev-actor-id")
            assertThat(preflight.statusCode()).isEqualTo(403)
            assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).isEmpty()
            listOf("/executions/id/events" to "GET", "/executions/id/inputs" to "POST").forEach { (path, method) ->
                assertThat(api.preflight(path, method, "x-dev-actor-id").statusCode()).isEqualTo(403)
                assertThat(api.preflight(path, method, "content-type").statusCode()).isEqualTo(200)
            }
            val actual = api.get("/users", "http://localhost:5173", "dev-thiago")
            assertThat(actual.statusCode()).isEqualTo(401)
            assertThat(
                actual.headers().allValues("Access-Control-Allow-Origin"),
            ).containsExactly("http://localhost:5173")
        }

        @Test
        fun `empty origins grant no browser access without breaking internal or proxy requests`() {
            val filter = CorsConfiguration().corsFilter(CorsProperties(), MockEnvironment())
            listOf(null, "http://localhost", "https://untrusted.example").forEach { origin ->
                val request = MockHttpServletRequest("GET", "/languages")
                if (origin != null) request.addHeader("Origin", origin)
                val response = MockHttpServletResponse()
                var reachedApplication = false
                filter.doFilter(request, response) { _, _ -> reachedApplication = true }
                assertThat(reachedApplication).isTrue()
                assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull()
            }
        }
    }
