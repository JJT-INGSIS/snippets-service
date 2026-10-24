package com.jjt.ingsis.snippets.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration

private const val RECIPIENT = "dev-ana"
private val sharePath = "/ownership/$snippetId/shares/$RECIPIENT"

private fun shareBody(recipient: String = RECIPIENT): String =
    """{"snippetId":"$snippetId","recipientId":"$recipient"}"""

private fun shareProblem(
    status: Int,
    category: String,
    relation: String? = null,
): String {
    val body = problemBody(status, category).replace("/ownership/$snippetId", sharePath)
    return if (relation == null) body else body.replace("}", """, "relation":"$relation"}""")
}

private fun registrar(service: StubPermissionsService): HttpShareRegistrar =
    HttpShareRegistrar(service.transport(), PermissionsResponseDecoder())

class ShareRegistrationTest {
    @Test
    fun `created and repeated shares require matching acknowledgments and a JSON actor body`() {
        listOf(201, 200).forEach { status ->
            StubPermissionsService(status, shareBody()).use { service ->
                assertThat(registrar(service).register(snippetId, OWNER, RECIPIENT))
                    .isEqualTo(ShareRegistration.Registered(created = status == 201))
                assertThat(service.received.single()).isEqualTo(
                    RecordedRequest(
                        method = "PUT",
                        path = sharePath,
                        body = """{"actorId":"$OWNER"}""",
                        contentType = "application/json",
                    ),
                )
            }
        }
    }

    @Test
    fun `recipient is a single encoded path segment and actor is exact JSON data`() {
        val recipient = " auth0|á +&?/#%=🚀 "
        val actor = " actor|á+ &?/#%=🚀 "
        StubPermissionsService(201, shareBody(recipient)).use { service ->
            assertThat(
                registrar(service).register(snippetId, actor, recipient),
            ).isEqualTo(ShareRegistration.Registered(true))
            val request = service.received.single()
            val segment = request.path.substringAfter("/shares/")
            assertThat(segment).doesNotContain("/", "?", "#")
            assertThat(URLDecoder.decode(segment, StandardCharsets.UTF_8)).isEqualTo(recipient)
            assertThat(request.body).isEqualTo("""{"actorId":"$actor"}""")
        }
    }

    @Test
    fun `denied shared and unrelated actors remain distinguishable`() {
        val denials = mapOf("shared" to ShareRegistration.Denied, "none" to ShareRegistration.Missing)
        denials.forEach { (relation, expected) ->
            StubPermissionsService(403, shareProblem(403, "share-denied", relation), "application/problem+json").use {
                assertThat(registrar(it).register(snippetId, OWNER, RECIPIENT)).isEqualTo(expected)
            }
        }
        StubPermissionsService(404, shareProblem(404, "ownership-not-found"), "application/problem+json").use {
            assertThat(registrar(it).register(snippetId, OWNER, RECIPIENT)).isEqualTo(ShareRegistration.Missing)
        }
        StubPermissionsService(422, shareProblem(422, "invalid-recipient"), "application/problem+json").use {
            assertThat(
                registrar(it).register(snippetId, OWNER, RECIPIENT),
            ).isEqualTo(ShareRegistration.InvalidRecipient)
        }
    }

    @Test
    fun `malformed successes never confirm a share`() {
        listOf(
            "{}",
            "null",
            "[]",
            "invalid",
            shareBody("other"),
            shareBody().replace(snippetId.toString(), "other"),
            shareBody() + " {}",
        ).forEach { body ->
            StubPermissionsService(201, body).use {
                assertThat(registrar(it).register(snippetId, OWNER, RECIPIENT))
                    .isEqualTo(ShareRegistration.Failed(PermissionsFailure.INVALID_RESPONSE))
            }
        }
        StubPermissionsService(200, shareBody(), "text/plain").use {
            assertThat(registrar(it).register(snippetId, OWNER, RECIPIENT))
                .isEqualTo(ShareRegistration.Failed(PermissionsFailure.INVALID_RESPONSE))
        }
    }

    @Test
    fun `untrusted errors cannot become a denial or a missing snippet`() {
        listOf(
            Triple(403, shareProblem(403, "share-denied", "owned"), "application/problem+json"),
            Triple(403, shareProblem(403, "share-denied"), "application/problem+json"),
            Triple(
                403,
                shareProblem(403, "share-denied", "shared").replace(sharePath, "/other"),
                "application/problem+json",
            ),
            Triple(404, "{}", "application/json"),
            Triple(500, shareBody(), "application/json"),
            Triple(202, shareBody(), "application/json"),
        ).forEach { (status, body, contentType) ->
            StubPermissionsService(status, body, contentType).use {
                assertThat(registrar(it).register(snippetId, OWNER, RECIPIENT))
                    .isEqualTo(ShareRegistration.Failed(PermissionsFailure.INVALID_RESPONSE))
            }
        }
    }

    @Test
    fun `a timeout or unreachable server is unconfirmed and is not automatically retried`() {
        StubPermissionsService(201, shareBody(), delay = Duration.ofMillis(350)).use { service ->
            val slow = HttpShareRegistrar(service.transport(Duration.ofMillis(100)), PermissionsResponseDecoder())
            assertThat(slow.register(snippetId, OWNER, RECIPIENT))
                .isEqualTo(ShareRegistration.Failed(PermissionsFailure.UNAVAILABLE))
            assertThat(service.received).hasSize(1)
        }
        val unavailable = PermissionsHttpTransport(PermissionsProperties(baseUrl = "http://127.0.0.1:1"))
        assertThat(HttpShareRegistrar(unavailable, PermissionsResponseDecoder()).register(snippetId, OWNER, RECIPIENT))
            .isEqualTo(ShareRegistration.Failed(PermissionsFailure.UNAVAILABLE))
    }
}
