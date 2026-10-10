package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.permissions.ModificationChecker
import com.jjt.ingsis.snippets.permissions.OwnershipReader
import com.jjt.ingsis.snippets.permissions.OwnershipRegistrar
import com.jjt.ingsis.snippets.update.PrepareUpdate
import com.jjt.ingsis.snippets.update.UpdatePreparationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:preparation;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
    ],
)
class PreparationWiringTest
    @Autowired
    constructor(
        private val preparation: PrepareCreation,
        private val registrar: OwnershipRegistrar,
        private val reader: OwnershipReader,
        private val update: PrepareUpdate,
        private val modification: ModificationChecker,
        @Value("\${local.server.port}") private val port: Int,
    ) {
        @Test
        fun `context wires preparation and clients without enabling development identity`() {
            assertNotNull(registrar)
            assertNotNull(reader)
            assertNotNull(modification)
            assertEquals(
                UpdatePreparationResult.Rejected(PreparationRejection.IdentityRejected(ActorIdentity.Disabled)),
                update.prepare(draft(), UUID.randomUUID().toString(), ActorContext(listOf("dev-thiago"))),
            )
            assertEquals(
                PreparationResult.Rejected(PreparationRejection.IdentityRejected(ActorIdentity.Disabled)),
                preparation.prepare(draft(), UUID.randomUUID().toString(), ActorContext(listOf("dev-thiago"))),
            )
        }

        @Test
        fun `creation requires an identity and the update endpoint remains unavailable`() {
            assertEquals(401, status("POST", "/snippets"))
            assertEquals(404, status("PUT", "/snippets/${UUID.randomUUID()}"))
        }

        private fun status(
            method: String,
            path: String,
        ): Int {
            val body = """{"name":"Example","language":"printscript","version":"1.0","code":"println(1);"}"""
            val request =
                HttpRequest
                    .newBuilder(URI("http://localhost:$port$path"))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("X-Dev-Actor-Id", "dev-thiago")
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
                    .build()

            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        }
    }
