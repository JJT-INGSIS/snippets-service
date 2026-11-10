package com.jjt.ingsis.snippets.http.security

import com.jjt.ingsis.snippets.execution.ControlledExecutor
import com.jjt.ingsis.snippets.http.AccessLedger
import com.jjt.ingsis.snippets.http.ScriptedHttpService
import com.jjt.ingsis.snippets.http.StubResponse
import com.jjt.ingsis.snippets.http.json
import com.jjt.ingsis.snippets.http.requestBody
import com.jjt.ingsis.snippets.language.Attributed
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.ProcessorIdentity
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.language.ValidationResult
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.language.linting.LintCatalogResult
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StoreResult
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "snippets.auth.mode=auth0",
        "snippets.auth.issuer=https://issuer.fixture/",
        "snippets.auth.audience=https://api.fixture",
        "snippets.cors.allowed-origins=http://127.0.0.1:5173",
        "execution.recovery.delay-ms=3600000",
    ],
)
@ActiveProfiles("dev")
@Import(MetadataTestConfiguration::class)
@MockitoBean(types = [Language::class])
class Auth0HttpTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") private val port: Int,
        private val jdbc: JdbcTemplate,
        private val storage: SnippetContentStorage,
        private val language: Language,
    ) {
        private val http = HttpClient.newHttpClient()
        private val executor = ControlledExecutor()

        @BeforeEach
        fun prepare() {
            permissions.reset()
            `when`(language.executorFor("printscript")).thenReturn(ExecutionResult.Success(executor))
            `when`(language.validate("printscript", "1.0", "println(1);")).thenReturn(ValidationResult.Valid)
            `when`(language.lintingCatalog("printscript", "1.0")).thenReturn(
                Attributed(
                    LintCatalogResult.Catalog(emptyList()),
                    ProcessorIdentity.Verified(ProcessorRevision("sha256-v1:" + "a".repeat(64))),
                ),
            )
        }

        @Test
        fun `creation records the subject and preserves idempotency across actors`() {
            val key = UUID.randomUUID().toString()
            val owner = token()
            val created = send(method = "POST", path = "/snippets", bearer = owner, body = requestBody(), key = key)
            assertThat(created.statusCode()).describedAs(created.body()).isEqualTo(201)
            val id = json.readTree(created.body()).path("id").asString()
            assertThat(
                jdbc.queryForObject(
                    "SELECT creation_actor_id FROM snippets WHERE id = ?",
                    String::class.java,
                    UUID.fromString(id),
                ),
            ).isEqualTo("auth0|alice")
            assertThat(
                send(method = "POST", path = "/snippets", bearer = owner, body = requestBody(), key = key)
                    .statusCode(),
            ).isEqualTo(200)
            assertThat(
                send(
                    method = "POST",
                    path = "/snippets",
                    bearer = token(subject = "auth0|bob"),
                    body = requestBody(),
                    key = key,
                ).statusCode(),
            ).isEqualTo(409)
            assertThat(get("/snippets/$id", owner).statusCode()).isEqualTo(200)
        }

        @Test
        fun `owner reader and stranger retain access rules despite forged owner header`() {
            val id = stored()
            ledger.share("auth0|alice", id, "auth0|bob")
            val path = "/snippets/$id"
            assertThat(get(path, token()).statusCode()).isEqualTo(200)
            assertThat(get(path, token(subject = "auth0|bob")).statusCode()).isEqualTo(200)
            assertThat(get(path, token(subject = "auth0|stranger")).statusCode()).isEqualTo(404)
            assertThat(
                send(method = "PUT", path = path, bearer = token(subject = "auth0|bob"), body = requestBody())
                    .statusCode(),
            ).isEqualTo(403)
            assertThat(send(method = "PUT", path = path, bearer = token(), body = requestBody()).statusCode())
                .isEqualTo(200)
            val listing = get("/snippets", token(subject = "auth0|stranger"))
            assertThat(listing.statusCode()).isEqualTo(200)
            assertThat(listing.body()).doesNotContain(id.toString())
        }

        @Test
        fun `real NDJSON reconnect and session mutations require current bearer and access`() {
            val id = stored()
            ledger.share("auth0|alice", id, "auth0|bob")
            val owner = token()
            val started = send("POST", "/snippets/$id/executions", owner)
            assertThat(started.statusCode()).describedAs(started.body()).isEqualTo(201)
            val run = json.readTree(started.body()).path("executionId").asString()
            val path = "/executions/$run"
            assertThat(get("$path/events", token(subject = "auth0|stranger")).statusCode()).isEqualTo(403)
            val input = """{"requestId":"${UUID.randomUUID()}","value":"Ana"}"""
            assertThat(
                send(method = "POST", path = "$path/inputs", bearer = token(subject = "auth0|bob"), body = input)
                    .statusCode(),
            ).isEqualTo(403)
            assertThat(send("POST", "$path/cancel", token(subject = "auth0|stranger")).statusCode()).isEqualTo(403)
            executor.inputResult.set(ExecutionResult.Success(Unit))
            assertThat(send(method = "POST", path = "$path/inputs", bearer = owner, body = input).statusCode())
                .isEqualTo(200)
            val events = get("$path/events", owner)
            assertThat(events.statusCode()).isEqualTo(200)
            assertThat(events.headers().firstValue("Content-Type").orElse("")).startsWith("application/x-ndjson")
            assertThat(
                events
                    .body()
                    .trim()
                    .lines()
                    .map(json::readTree)
                    .last()
                    .path("type")
                    .asString(),
            ).isEqualTo("terminal")
            val expired = token(expiration = Instant.now().minusSeconds(120))
            assertThat(get("$path/events?after=1", expired).statusCode()).isEqualTo(401)
            assertThat(send(method = "POST", path = "$path/inputs", bearer = expired, body = input).statusCode())
                .isEqualTo(401)
            assertThat(send("POST", "$path/cancel", expired).statusCode()).isEqualTo(401)
            assertThat(get(path, owner).statusCode()).isEqualTo(200)
            assertThat(get("$path/events?after=1", owner).statusCode()).isEqualTo(200)
        }

        private fun stored(): UUID {
            val id = UUID.randomUUID()
            val reference = (storage.store("println(1);") as StoreResult.Stored).reference
            jdbc.update(
                "INSERT INTO snippets (id, name, language, version, state, creation_key, creation_actor_id, " +
                    "creation_fingerprint, content_reference) " +
                    "VALUES (?, 'auth', 'printscript', '1.0', 'CONFIRMED', ?, 'auth0|alice', 'f', ?)",
                id,
                UUID.randomUUID(),
                reference.value,
            )
            ledger.own("auth0|alice", id)
            return id
        }

        @Test
        fun `real protected routes reject missing bearer even with forged dev actor`() {
            mapOf(
                "/snippets" to listOf("GET", "POST"),
                "/snippets/id" to listOf("GET", "PUT"),
                "/snippets/id/formatted" to listOf("GET"),
                "/snippets/id/shares/user" to listOf("PUT"),
                "/users" to listOf("GET"),
                "/formatting-rules/printscript/1.1" to listOf("GET", "PUT"),
                "/linting-rules/printscript/1.1" to listOf("GET", "PUT"),
                "/snippets/id/tests" to listOf("GET", "POST"),
                "/snippets/id/tests/test/executions" to listOf("POST"),
                "/snippets/id/executions" to listOf("POST"),
                "/executions/id" to listOf("GET"),
                "/executions/id/events" to listOf("GET"),
                "/executions/id/inputs" to listOf("POST"),
                "/executions/id/cancel" to listOf("POST"),
                "/jobs/id" to listOf("GET"),
                "/snippets/id/automatic-tests" to listOf("GET"),
            ).forEach { (path, methods) ->
                methods.forEach { method ->
                    val answer = send(method, path, null)
                    assertThat(answer.statusCode()).describedAs("$method $path").isEqualTo(401)
                    assertThat(answer.headers().firstValue("WWW-Authenticate").orElse("")).startsWith("Bearer")
                }
            }
        }

        @Test
        fun `missing directory credentials only disable the directory and never fall back to dev users`() {
            val answer = get("/users", token())
            assertThat(answer.statusCode()).isEqualTo(502)
            assertThat(answer.body()).contains("directory-unavailable").doesNotContain("dev-ana")
            assertThat(get("/languages", null).statusCode()).isEqualTo(200)
        }

        @Test
        fun `invalid signatures issuers audiences times and machine identities are rejected`() {
            listOf(
                token(key = otherKey),
                token(issuer = "https://other.fixture/"),
                token(audience = "spa-client-id"),
                token(expiration = Instant.now().minusSeconds(120)),
                token(subject = "client@clients", grant = "client-credentials"),
                token(subject = ""),
                token(expiration = null),
                token(notBefore = Instant.now().plusSeconds(120)),
                "not.a.jwt",
            ).forEach { bearer -> assertThat(get("/users", bearer).statusCode()).isEqualTo(401) }
        }

        @Test
        fun `preflight permits bearer on lint but rejects untrusted origin`() {
            fun preflight(origin: String) =
                http.send(
                    HttpRequest
                        .newBuilder(URI("http://127.0.0.1:$port/linting-rules/printscript/1.1"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .header("Origin", origin)
                        .header(
                            "Access-Control-Request-Method",
                            "PUT",
                        ).header("Access-Control-Request-Headers", "authorization,content-type")
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertThat(preflight("http://127.0.0.1:5173").statusCode()).isEqualTo(200)
            assertThat(preflight("https://untrusted.fixture").statusCode()).isEqualTo(403)
        }

        private fun get(
            path: String,
            bearer: String?,
        ): HttpResponse<String> = send("GET", path, bearer)

        private fun send(
            method: String,
            path: String,
            bearer: String?,
            body: String = "{}",
            key: String? = null,
        ): HttpResponse<String> {
            val request =
                HttpRequest
                    .newBuilder(URI("http://127.0.0.1:$port$path"))
                    .header("X-Dev-Actor-Id", "auth0|alice")
                    .header("Content-Type", "application/json")
            if (bearer != null) request.header("Authorization", "Bearer $bearer")
            if (key != null) request.header("Idempotency-Key", key)
            return http.send(
                request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }

        companion object {
            private val ledger = AccessLedger()
            private val permissions =
                ScriptedHttpService { request ->
                    if (request.method == "PUT" && request.path.startsWith("/ownership/")) {
                        val id = UUID.fromString(request.path.substringAfterLast('/'))
                        val owner = json.readTree(request.body).path("ownerId").asString()
                        ledger.own(owner, id)
                        StubResponse(
                            201,
                            json.writeValueAsString(mapOf("snippetId" to id.toString(), "ownerId" to owner)),
                        )
                    } else {
                        ledger.answer(request)
                    }
                }

            private fun key(): RSAKey {
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                return RSAKey
                    .Builder(
                        pair.public as RSAPublicKey,
                    ).privateKey(pair.private as RSAPrivateKey)
                    .keyID("fixture")
                    .build()
            }

            private val signingKey = key()
            private val otherKey = key()
            private val server =
                HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                    createContext("/jwks") { exchange ->
                        val bytes = JWKSet(signingKey.toPublicJWK()).toString().toByteArray()
                        exchange.responseHeaders.set("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                    start()
                }

            @JvmStatic @DynamicPropertySource
            fun properties(registry: DynamicPropertyRegistry) {
                registry.add("snippets.auth.jwk-set-uri") { "http://127.0.0.1:${server.address.port}/jwks" }
                registry.add("permissions.base-url") { permissions.baseUrl }
                registry.add("storage.local.directory") { Files.createTempDirectory("auth0-http").toString() }
            }

            @JvmStatic @AfterAll
            fun stop() {
                server.stop(0)
                permissions.close()
            }

            // Security cases vary independent claims explicitly, without a production token builder.
            @Suppress("LongParameterList")
            private fun token(
                key: RSAKey = signingKey,
                issuer: String = "https://issuer.fixture/",
                audience: String = "https://api.fixture",
                subject: String = "auth0|alice",
                expiration: Instant? = Instant.now().plusSeconds(300),
                notBefore: Instant = Instant.now().minusSeconds(1),
                grant: String = "authorization_code",
            ): String {
                val claims =
                    JWTClaimsSet
                        .Builder()
                        .issuer(issuer)
                        .audience(audience)
                        .subject(subject)
                        .notBeforeTime(Date.from(notBefore))
                        .claim("gty", grant)
                if (expiration != null) claims.expirationTime(Date.from(expiration))
                val jwt =
                    SignedJWT(
                        JWSHeader
                            .Builder(JWSAlgorithm.RS256)
                            .type(JOSEObjectType.JWT)
                            .keyID("fixture")
                            .build(),
                        claims.build(),
                    )
                jwt.sign(RSASSASigner(key))
                return jwt.serialize()
            }
        }
    }
