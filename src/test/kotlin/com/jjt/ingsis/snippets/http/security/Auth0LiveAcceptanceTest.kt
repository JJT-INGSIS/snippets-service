package com.jjt.ingsis.snippets.http.security

import com.jjt.ingsis.snippets.http.json
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val ISSUER = "https://jjt-snippet-searcher.us.auth0.com/"
private const val AUDIENCE = "https://jjt-snippets-dev.duckdns.org/api"
private const val CLIENT = "BDuvRyQ6ryyye08Bs9ZEfRzw7AsskFs6"
private const val CALLBACK = "http://127.0.0.1:5173/auth/callback"

/** Opt-in diagnostic only: Universal Login owns credentials; no token is printed or persisted. */
@EnabledIfEnvironmentVariable(named = "AUTH0_LIVE_ACCEPTANCE", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:auth0live;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
        "snippets.auth.mode=auth0", "snippets.auth.issuer=$ISSUER", "snippets.auth.audience=$AUDIENCE",
    ],
)
@ActiveProfiles("dev")
class Auth0LiveAcceptanceTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") private val port: Int,
    ) {
        @Test
        fun `real PKCE user token is accepted through the API security chain`() {
            val verifier = random()
            val state = random()
            val code = CompletableFuture<String>()
            val callback = HttpServer.create(InetSocketAddress("127.0.0.1", 5173), 0)
            val link = Path.of("build", "sni72-login-url.txt")
            callback.createContext("/auth/callback") { exchange ->
                val query =
                    exchange.requestURI.rawQuery.orEmpty().split('&').associate {
                        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
                    }
                val accepted = query["state"] == state && !query["code"].isNullOrBlank()
                val text = "Autorizacion recibida. Podes volver a Codex.".toByteArray()
                exchange.sendResponseHeaders(if (accepted) 200 else 400, text.size.toLong())
                exchange.responseBody.use { it.write(text) }
                if (accepted) code.complete(checkNotNull(query["code"]))
            }
            callback.start()
            try {
                val challenge =
                    Base64
                        .getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
                        )
                val parameters =
                    mapOf(
                        "response_type" to "code",
                        "client_id" to CLIENT,
                        "redirect_uri" to CALLBACK,
                        "audience" to AUDIENCE,
                        "scope" to "openid profile email",
                        "state" to state,
                        "code_challenge" to challenge,
                        "code_challenge_method" to "S256",
                    )
                Files.writeString(
                    link,
                    ISSUER + "authorize?" +
                        parameters.entries.joinToString("&") {
                            it.key + "=" + URLEncoder.encode(it.value, Charsets.UTF_8)
                        },
                )
                val token = exchangeCode(code.get(10, TimeUnit.MINUTES), verifier)
                verifyApi(token)
            } finally {
                callback.stop(0)
                Files.deleteIfExists(link)
            }
        }

        private fun verifyApi(token: String) {
            val http = HttpClient.newHttpClient()
            val endpoint = URI("http://127.0.0.1:$port/users")
            val valid =
                HttpRequest
                    .newBuilder(endpoint)
                    .header("Authorization", "Bearer $token")
                    .GET()
                    .build()
            assertThat(http.send(valid, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200)
            val forged =
                HttpRequest
                    .newBuilder(endpoint)
                    .header("X-Dev-Actor-Id", "dev-juan")
                    .GET()
                    .build()
            assertThat(http.send(forged, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401)
        }

        private fun exchangeCode(
            code: String,
            verifier: String,
        ): String {
            val body =
                json.writeValueAsString(
                    mapOf(
                        "grant_type" to "authorization_code",
                        "client_id" to CLIENT,
                        "code" to code,
                        "redirect_uri" to CALLBACK,
                        "code_verifier" to verifier,
                    ),
                )
            val request =
                HttpRequest
                    .newBuilder(URI(ISSUER + "oauth/token"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            assertThat(response.statusCode()).isEqualTo(200)
            return json.readTree(response.body()).path("access_token").asString()
        }

        private fun random(): String =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    }
