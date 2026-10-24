package com.jjt.ingsis.snippets.permissions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

private val sharedId: UUID = UUID.fromString("7d2e9a4f-1b3c-4e5d-8f6a-0b1c2d3e4f50")

private fun listing(actor: String = OWNER): String =
    """
    {"actorId":"$actor","snippets":[
    {"snippetId":"$snippetId","ownerId":"$actor","relation":"owned"},
    {"snippetId":"$sharedId","ownerId":"dev-juan","relation":"shared"}]}
    """.trimIndent()

private fun lister(service: StubPermissionsService): HttpAccessLister =
    HttpAccessLister(service.transport(), PermissionsResponseDecoder())

class AccessListingTest {
    @Test
    fun `owned and shared accesses are decoded with their owner`() {
        StubPermissionsService(200, listing()).use { service ->
            assertEquals(
                AccessListing.Listed(
                    listOf(
                        SnippetAccess(snippetId, OWNER, AccessRelation.OWNED),
                        SnippetAccess(sharedId, "dev-juan", AccessRelation.SHARED),
                    ),
                ),
                lister(service).list(OWNER, RelationFilter.ALL),
            )
            assertEquals("GET", service.received.single().method)
        }
    }

    @Test
    fun `actor and relation travel encoded in the query`() {
        val actor = " auth0|Thiago+á &?/#%=🚀 "
        StubPermissionsService(200, """{"actorId":"$actor","snippets":[]}""").use { service ->
            assertEquals(AccessListing.Listed(emptyList()), lister(service).list(actor, RelationFilter.SHARED))
            val path = service.received.single().path
            assertTrue(path.startsWith("/access?actorId="))
            val pairs = path.substringAfter('?').split('&')
            val query = pairs.associate { it.substringBefore('=') to it.substringAfter('=') }
            assertEquals(actor, URLDecoder.decode(query.getValue("actorId"), StandardCharsets.UTF_8))
            assertEquals("shared", query.getValue("relation"))
        }
    }

    @Test
    fun `answers that break the contract are never an empty or partial list`() {
        listOf(
            "{}",
            "[]",
            "",
            "invalid-json",
            """{"actorId":"someone-else","snippets":[]}""",
            """{"actorId":"$OWNER","snippets":{}}""",
            """{"actorId":"$OWNER","snippets":[{"snippetId":"not-a-uuid","ownerId":"x","relation":"owned"}]}""",
            """{"actorId":"$OWNER","snippets":[{"snippetId":"$snippetId","ownerId":" ","relation":"owned"}]}""",
            """{"actorId":"$OWNER","snippets":[{"snippetId":"$snippetId","ownerId":"x","relation":"none"}]}""",
            """{"actorId":"$OWNER","snippets":[{"snippetId":"$snippetId","ownerId":"x","relation":"OWNED"}]}""",
            listing().replace(sharedId.toString(), snippetId.toString()),
            listing().replace(snippetId.toString(), snippetId.toString().uppercase()),
        ).forEach { body ->
            StubPermissionsService(200, body).use { service ->
                assertEquals(
                    AccessListing.Failed(PermissionsFailure.INVALID_RESPONSE),
                    lister(service).list(OWNER, RelationFilter.ALL),
                    body,
                )
            }
        }
    }

    @Test
    fun `problems and unexpected statuses are failures`() {
        val badRequest =
            """{"type":"urn:permissions:problem:invalid-request","status":400,"title":"Bad Request",""" +
                """"detail":"actorId must not be blank.","instance":"/access"}"""
        StubPermissionsService(400, badRequest, "application/problem+json").use { service ->
            assertEquals(
                AccessListing.Failed(PermissionsFailure.INVALID_REQUEST),
                lister(service).list(OWNER, RelationFilter.ALL),
            )
        }
        listOf(404, 500, 503).forEach { status ->
            StubPermissionsService(status, listing()).use { service ->
                assertEquals(
                    AccessListing.Failed(PermissionsFailure.INVALID_RESPONSE),
                    lister(service).list(OWNER, RelationFilter.ALL),
                )
            }
        }
        StubPermissionsService(200, listing(), "text/html").use { service ->
            assertEquals(
                AccessListing.Failed(PermissionsFailure.INVALID_RESPONSE),
                lister(service).list(OWNER, RelationFilter.ALL),
            )
        }
    }

    @Test
    fun `timeout missing server and blank actor are failures without retries`() {
        StubPermissionsService(200, listing(), delay = Duration.ofMillis(300)).use { service ->
            val slow = HttpAccessLister(service.transport(Duration.ofMillis(100)), PermissionsResponseDecoder())
            assertEquals(AccessListing.Failed(PermissionsFailure.UNAVAILABLE), slow.list(OWNER, RelationFilter.ALL))
            assertEquals(1, service.received.size)
            assertEquals(
                AccessListing.Failed(PermissionsFailure.INVALID_REQUEST),
                lister(service).list(" ", RelationFilter.ALL),
            )
            assertEquals(1, service.received.size)
        }
        val transport = PermissionsHttpTransport(PermissionsProperties(baseUrl = "http://127.0.0.1:1"))
        assertEquals(
            AccessListing.Failed(PermissionsFailure.UNAVAILABLE),
            HttpAccessLister(transport, PermissionsResponseDecoder()).list(OWNER, RelationFilter.ALL),
        )
    }
}
