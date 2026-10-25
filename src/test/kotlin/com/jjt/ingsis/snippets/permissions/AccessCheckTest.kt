package com.jjt.ingsis.snippets.permissions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration

private val accessPath = "/ownership/$snippetId/access"

private fun relation(
    relation: String,
    owner: String = OWNER,
): String = """{"snippetId":"$snippetId","ownerId":"$owner","relation":"$relation"}"""

private fun accessProblem(
    status: Int,
    category: String,
): String = problemBody(status, category).replace("/ownership/$snippetId", accessPath)

private fun checker(service: StubPermissionsService): HttpAccessChecker =
    HttpAccessChecker(service.transport(), PermissionsResponseDecoder())

class AccessCheckTest {
    @Test
    fun `owned and shared relations grant access with the owner`() {
        StubPermissionsService(200, relation("owned")).use { service ->
            assertEquals(
                AccessLookup.Granted(SnippetAccess(snippetId, OWNER, AccessRelation.OWNED)),
                checker(service).check(snippetId, OWNER),
            )
            assertEquals("GET", service.received.single().method)
        }
        StubPermissionsService(200, relation("shared")).use { service ->
            assertEquals(
                AccessLookup.Granted(SnippetAccess(snippetId, OWNER, AccessRelation.SHARED)),
                checker(service).check(snippetId, "dev-juan"),
            )
        }
    }

    @Test
    fun `none is a denial and a missing owner is distinct`() {
        StubPermissionsService(200, relation("none")).use { service ->
            assertEquals(AccessLookup.Denied, checker(service).check(snippetId, "dev-ana"))
        }
        StubPermissionsService(404, accessProblem(404, "ownership-not-found"), "application/problem+json")
            .use { service -> assertEquals(AccessLookup.Missing, checker(service).check(snippetId, "dev-ana")) }
        StubPermissionsService(400, accessProblem(400, "invalid-request"), "application/problem+json").use { service ->
            assertEquals(
                AccessLookup.Failed(PermissionsFailure.INVALID_REQUEST),
                checker(service).check(snippetId, "dev-ana"),
            )
        }
    }

    @Test
    fun `the actor travels encoded in the query`() {
        val actor = " auth0|Thiago+á &?/#%=🚀 "
        StubPermissionsService(200, relation("shared")).use { service ->
            checker(service).check(snippetId, actor)
            val path = service.received.single().path
            assertTrue(path.startsWith("$accessPath?actorId="))
            assertEquals(actor, URLDecoder.decode(path.substringAfter("?actorId="), StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `answers that break the contract never grant or deny access`() {
        listOf(
            "{}",
            "",
            "invalid-json",
            relation("owned").replace(snippetId.toString(), "7d2e9a4f-1b3c-4e5d-8f6a-0b1c2d3e4f50"),
            relation("owned", owner = " "),
            relation("OWNED"),
            relation("reader"),
            """{"snippetId":"$snippetId","relation":"owned"}""",
        ).forEach { body ->
            StubPermissionsService(200, body).use { service ->
                assertEquals(
                    AccessLookup.Failed(PermissionsFailure.INVALID_RESPONSE),
                    checker(service).check(snippetId, OWNER),
                    body,
                )
            }
        }
        listOf(403, 404, 500, 503).forEach { status ->
            StubPermissionsService(status, "{}").use { service ->
                assertEquals(
                    AccessLookup.Failed(PermissionsFailure.INVALID_RESPONSE),
                    checker(service).check(snippetId, OWNER),
                )
            }
        }
    }

    @Test
    fun `timeout missing server and blank actor are failures without retries`() {
        StubPermissionsService(200, relation("owned"), delay = Duration.ofMillis(300)).use { service ->
            val slow = HttpAccessChecker(service.transport(Duration.ofMillis(100)), PermissionsResponseDecoder())
            assertEquals(AccessLookup.Failed(PermissionsFailure.UNAVAILABLE), slow.check(snippetId, OWNER))
            assertEquals(1, service.received.size)
            assertEquals(
                AccessLookup.Failed(PermissionsFailure.INVALID_REQUEST),
                checker(service).check(snippetId, " "),
            )
            assertEquals(1, service.received.size)
        }
        val transport = PermissionsHttpTransport(PermissionsProperties(baseUrl = "http://127.0.0.1:1"))
        assertEquals(
            AccessLookup.Failed(PermissionsFailure.UNAVAILABLE),
            HttpAccessChecker(transport, PermissionsResponseDecoder()).check(snippetId, OWNER),
        )
    }
}
