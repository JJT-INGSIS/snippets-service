package com.jjt.ingsis.snippets.permissions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class OwnershipResponseTest {
    @Test
    fun `registration rejects incompatible success bodies`() {
        val bodies =
            listOf(
                "",
                "not-json",
                "[]",
                "{}",
                ownershipBody("another-owner"),
                ownershipBody().replace(snippetId.toString(), "1-1-1-1-1"),
                ownershipBody().replace("\"$OWNER\"", "123"),
                ownershipBody().replace("\"$OWNER\"", "\" \""),
            )
        bodies.forEach { body ->
            StubPermissionsService(200, body).use { service ->
                val registrar = HttpOwnershipRegistrar(service.transport(), PermissionsResponseDecoder())
                assertEquals(
                    OwnershipRegistration.Failed(PermissionsFailure.INVALID_RESPONSE, outcomeUncertain = true),
                    registrar.register(snippetId, OWNER),
                )
            }
        }
    }

    @Test
    fun `wrong content type or malformed problem never become a conflict`() {
        StubPermissionsService(200, ownershipBody(), "text/html").use { service ->
            val registrar = HttpOwnershipRegistrar(service.transport(), PermissionsResponseDecoder())
            assertInstanceOf(OwnershipRegistration.Failed::class.java, registrar.register(snippetId, OWNER))
        }
        listOf(
            problemBody(409, "technical-failure"),
            problemBody(409, "owner-conflict").replace("\"status\":409", "\"status\":500"),
            problemBody(409, "owner-conflict").replace("\"status\":409", "\"status\":4294967705"),
            problemBody(409, "owner-conflict").replace(snippetId.toString(), "another-path"),
            "{}",
        ).forEach { body ->
            StubPermissionsService(409, body, "application/problem+json").use { service ->
                val registrar = HttpOwnershipRegistrar(service.transport(), PermissionsResponseDecoder())
                assertInstanceOf(OwnershipRegistration.Failed::class.java, registrar.register(snippetId, OWNER))
            }
        }
    }

    @Test
    fun `JSON charset parameters are accepted`() {
        StubPermissionsService(201, ownershipBody(), "application/json; charset=UTF-8").use { service ->
            val registrar = HttpOwnershipRegistrar(service.transport(), PermissionsResponseDecoder())
            assertInstanceOf(OwnershipRegistration.Registered::class.java, registrar.register(snippetId, OWNER))
        }
    }

    @Test
    fun `invalid Content-Type is returned as failure rather than thrown`() {
        StubPermissionsService(201, ownershipBody(), "invalid-content-type").use { service ->
            val registrar = HttpOwnershipRegistrar(service.transport(), PermissionsResponseDecoder())
            assertInstanceOf(OwnershipRegistration.Failed::class.java, registrar.register(snippetId, OWNER))
        }
    }
}
