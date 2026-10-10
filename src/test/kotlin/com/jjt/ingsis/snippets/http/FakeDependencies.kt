package com.jjt.ingsis.snippets.http

import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val mapper = JsonMapper.builder().build()

internal val acceptedCode = StubResponse(200, """{"valid":true,"diagnostics":[]}""")

internal class OwnershipLedger {
    private val owners = ConcurrentHashMap<String, String>()

    fun ownerOf(snippetId: UUID): String? = owners["/ownership/$snippetId"]

    fun register(request: StubRequest): StubResponse {
        val snippetId = request.path.substringAfterLast('/')
        val ownerId = mapper.readTree(request.body).get("ownerId").asString()
        val previous = owners.putIfAbsent(request.path, ownerId)

        return when (previous) {
            null -> StubResponse(201, ownership(snippetId, ownerId))
            ownerId -> StubResponse(200, ownership(snippetId, ownerId))
            else -> ownerConflict(request.path)
        }
    }

    private fun ownership(
        snippetId: String,
        ownerId: String,
    ): String = mapper.writeValueAsString(mapOf("snippetId" to snippetId, "ownerId" to ownerId))
}

internal fun ownerConflict(path: String): StubResponse =
    StubResponse(
        status = 409,
        body =
            """
            {"type":"urn:permissions:problem:owner-conflict","title":"Conflict","status":409,
            "detail":"The snippet already has a different owner.","instance":"$path"}
            """.trimIndent(),
        contentType = "application/problem+json",
    )
