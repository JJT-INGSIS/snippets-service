package com.jjt.ingsis.snippets.permissions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

class OwnershipRegistrationTest {
    @Test
    fun `new and repeated registrations validate the returned UUID and owner`() {
        listOf(201, 200).forEach { status ->
            StubPermissionsService(status, ownershipBody()).use { service ->
                val registrar = HttpOwnershipRegistrar(service.transport(), PermissionsResponseDecoder())
                assertEquals(
                    OwnershipRegistration.Registered(Ownership(snippetId, OWNER), created = status == 201),
                    registrar.register(snippetId, OWNER),
                )
                val request = service.received.single()
                assertEquals("PUT", request.method)
                assertEquals("/ownership/$snippetId", request.path)
                assertEquals("application/json", request.contentType)
                assertEquals(
                    OWNER,
                    JsonMapper
                        .builder()
                        .build()
                        .readTree(request.body)
                        .get("ownerId")
                        .asString(),
                )
            }
        }
    }

    @Test
    fun `conflict uses the stable problem identifier not human wording`() {
        StubPermissionsService(409, problemBody(409, "owner-conflict"), "application/problem+json").use { service ->
            val registrar = HttpOwnershipRegistrar(service.transport(), PermissionsResponseDecoder())
            assertEquals(OwnershipRegistration.Conflict, registrar.register(snippetId, OWNER))
        }
    }

    @Test
    fun `malformed request response is our integration failure not invalid code`() {
        StubPermissionsService(400, problemBody(400, "invalid-request"), "application/problem+json").use { service ->
            val registrar = HttpOwnershipRegistrar(service.transport(), PermissionsResponseDecoder())
            assertEquals(
                OwnershipRegistration.Failed(PermissionsFailure.INVALID_REQUEST, outcomeUncertain = false),
                registrar.register(snippetId, OWNER),
            )
        }
    }

    @Test
    fun `blank owners are rejected locally without calling the service`() {
        StubPermissionsService(201, ownershipBody()).use { service ->
            val registrar = HttpOwnershipRegistrar(service.transport(), PermissionsResponseDecoder())
            assertEquals(
                OwnershipRegistration.Failed(PermissionsFailure.INVALID_REQUEST, outcomeUncertain = false),
                registrar.register(snippetId, " "),
            )
            assertTrue(service.received.isEmpty())
        }
    }

    @Test
    fun `timeout is uncertain and does not automatically retry`() {
        StubPermissionsService(201, ownershipBody(), delay = Duration.ofMillis(300)).use { service ->
            val registrar =
                HttpOwnershipRegistrar(service.transport(Duration.ofMillis(100)), PermissionsResponseDecoder())
            assertEquals(
                OwnershipRegistration.Failed(PermissionsFailure.UNAVAILABLE, outcomeUncertain = true),
                registrar.register(snippetId, OWNER),
            )
            assertEquals(1, service.received.size)
        }
    }

    @Test
    fun `connection failure never becomes ownership conflict`() {
        val transport = PermissionsHttpTransport(PermissionsProperties(baseUrl = "http://127.0.0.1:1"))
        val result = HttpOwnershipRegistrar(transport, PermissionsResponseDecoder()).register(snippetId, OWNER)
        assertEquals(OwnershipRegistration.Failed(PermissionsFailure.UNAVAILABLE, outcomeUncertain = true), result)
    }

    @Test
    fun `server and unexpected HTTP errors are technical failures`() {
        listOf(500, 503, 404, 422, 302).forEach { status ->
            StubPermissionsService(
                status = status,
                body = problemBody(status, "technical-failure"),
                contentType = "application/problem+json",
            ).use { service ->
                val registrar = HttpOwnershipRegistrar(service.transport(), PermissionsResponseDecoder())
                assertInstanceOf(OwnershipRegistration.Failed::class.java, registrar.register(snippetId, OWNER))
            }
        }
    }
}
