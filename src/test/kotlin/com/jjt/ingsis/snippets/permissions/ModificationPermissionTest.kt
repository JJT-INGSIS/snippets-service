package com.jjt.ingsis.snippets.permissions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration

private fun modificationProblem(
    status: Int,
    category: String,
): String = problemBody(status, category).replace("/ownership/$snippetId", "/ownership/$snippetId/can-modify")

class ModificationPermissionTest {
    @Test
    fun `allowed and denied are boolean values not HTTP failures`() {
        listOf(true, false).forEach { allowed ->
            StubPermissionsService(200, """{"allowed":$allowed}""").use { service ->
                val checker = HttpModificationChecker(service.transport(), PermissionsResponseDecoder())
                assertEquals(
                    if (allowed) ModificationPermission.Allowed else ModificationPermission.Denied,
                    checker.check(snippetId, OWNER),
                )
                assertEquals("GET", service.received.single().method)
            }
        }
    }

    @Test
    fun `opaque actor query is encoded without losing plus spaces unicode or reserved characters`() {
        val actor = " auth0|Thiago+á &?/#%=🚀 "
        StubPermissionsService(200, """{"allowed":true}""").use { service ->
            val checker = HttpModificationChecker(service.transport(), PermissionsResponseDecoder())
            assertEquals(ModificationPermission.Allowed, checker.check(snippetId, actor))
            val path = service.received.single().path
            assertTrue(path.startsWith("/ownership/$snippetId/can-modify?actorId="))
            val value = path.substringAfter("?actorId=")
            assertTrue(value.contains("%2B", ignoreCase = true))
            assertEquals(actor, URLDecoder.decode(value, StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `missing ownership and bad request have distinct results`() {
        StubPermissionsService(
            status = 404,
            body = modificationProblem(404, "ownership-not-found"),
            contentType = "application/problem+json",
        ).use { service ->
            val checker = HttpModificationChecker(service.transport(), PermissionsResponseDecoder())
            assertEquals(ModificationPermission.OwnershipMissing, checker.check(snippetId, OWNER))
        }
        StubPermissionsService(
            status = 400,
            body = modificationProblem(400, "invalid-request"),
            contentType = "application/problem+json",
        ).use { service ->
            val checker = HttpModificationChecker(service.transport(), PermissionsResponseDecoder())
            assertEquals(
                ModificationPermission.Failed(PermissionsFailure.INVALID_REQUEST),
                checker.check(snippetId, OWNER),
            )
        }
    }

    @Test
    fun `malformed success and errors never become permission denial`() {
        listOf("{}", "[]", "", "invalid-json", """{"allowed":1}""", """{"allowed":"false"}""", """{"allowed":null}""")
            .forEach { body ->
                StubPermissionsService(200, body).use { service ->
                    val checker = HttpModificationChecker(service.transport(), PermissionsResponseDecoder())
                    assertEquals(
                        ModificationPermission.Failed(PermissionsFailure.INVALID_RESPONSE),
                        checker.check(snippetId, OWNER),
                    )
                }
            }
        listOf(403, 404, 500, 503).forEach { status ->
            StubPermissionsService(status, "{}").use { service ->
                val checker = HttpModificationChecker(service.transport(), PermissionsResponseDecoder())
                assertInstanceOf(ModificationPermission.Failed::class.java, checker.check(snippetId, OWNER))
            }
        }
    }

    @Test
    fun `timeout and missing server are failures without retries`() {
        StubPermissionsService(200, """{"allowed":true}""", delay = Duration.ofMillis(300)).use { service ->
            val checker =
                HttpModificationChecker(service.transport(Duration.ofMillis(100)), PermissionsResponseDecoder())
            assertEquals(ModificationPermission.Failed(PermissionsFailure.UNAVAILABLE), checker.check(snippetId, OWNER))
            assertEquals(1, service.received.size)
        }
        val transport = PermissionsHttpTransport(PermissionsProperties(baseUrl = "http://127.0.0.1:1"))
        assertEquals(
            ModificationPermission.Failed(PermissionsFailure.UNAVAILABLE),
            HttpModificationChecker(transport, PermissionsResponseDecoder()).check(snippetId, OWNER),
        )
    }

    @Test
    fun `blank actors and unexpected content types cannot grant permission`() {
        StubPermissionsService(200, """{"allowed":true}""", "text/html").use { service ->
            val checker = HttpModificationChecker(service.transport(), PermissionsResponseDecoder())
            assertEquals(
                ModificationPermission.Failed(PermissionsFailure.INVALID_REQUEST),
                checker.check(snippetId, " "),
            )
            assertTrue(service.received.isEmpty())
            assertEquals(
                ModificationPermission.Failed(PermissionsFailure.INVALID_RESPONSE),
                checker.check(snippetId, OWNER),
            )
        }
    }
}
