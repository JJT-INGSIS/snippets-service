package com.jjt.ingsis.snippets.permissions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.time.Duration

class OwnershipLookupTest {
    @Test
    fun `lookup preserves the exact opaque owner`() {
        val owner = " auth0|Thiago+One "
        StubPermissionsService(200, ownershipBody(owner)).use { service ->
            val reader = HttpOwnershipReader(service.transport(), PermissionsResponseDecoder())
            assertEquals(OwnershipLookup.Found(Ownership(snippetId, owner)), reader.find(snippetId))
            assertEquals("GET", service.received.single().method)
        }
    }

    @Test
    fun `missing ownership is distinguished from a malformed request`() {
        StubPermissionsService(
            status = 404,
            body = problemBody(404, "ownership-not-found"),
            contentType = "application/problem+json",
        ).use { service ->
            val reader = HttpOwnershipReader(service.transport(), PermissionsResponseDecoder())
            assertEquals(OwnershipLookup.Missing, reader.find(snippetId))
        }
        StubPermissionsService(400, problemBody(400, "invalid-request"), "application/problem+json").use { service ->
            val reader = HttpOwnershipReader(service.transport(), PermissionsResponseDecoder())
            assertEquals(OwnershipLookup.Failed(PermissionsFailure.INVALID_REQUEST), reader.find(snippetId))
        }
    }

    @Test
    fun `server errors and malformed responses never become missing ownership`() {
        listOf(500, 503, 200, 404).forEach { status ->
            StubPermissionsService(status, "{}").use { service ->
                val reader = HttpOwnershipReader(service.transport(), PermissionsResponseDecoder())
                assertInstanceOf(OwnershipLookup.Failed::class.java, reader.find(snippetId))
            }
        }
        StubPermissionsService(200, ownershipBody().replace(snippetId.toString(), "another-id")).use { service ->
            val reader = HttpOwnershipReader(service.transport(), PermissionsResponseDecoder())
            assertInstanceOf(OwnershipLookup.Failed::class.java, reader.find(snippetId))
        }
    }

    @Test
    fun `lookup timeout is a failure and does not retry`() {
        StubPermissionsService(200, ownershipBody(), delay = Duration.ofMillis(300)).use { service ->
            val reader = HttpOwnershipReader(service.transport(Duration.ofMillis(100)), PermissionsResponseDecoder())
            assertEquals(OwnershipLookup.Failed(PermissionsFailure.UNAVAILABLE), reader.find(snippetId))
            assertEquals(1, service.received.size)
        }
    }
}
