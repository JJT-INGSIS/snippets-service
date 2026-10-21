package com.jjt.ingsis.snippets.http

import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val CAN_MODIFY = "/can-modify"

private val mapper = JsonMapper.builder().build()

internal val acceptedCode = StubResponse(200, """{"valid":true,"diagnostics":[]}""")

internal class OwnershipLedger {
    private val owners = ConcurrentHashMap<String, String>()

    fun ownerOf(snippetId: UUID): String? = owners["/ownership/$snippetId"]

    fun answer(request: StubRequest): StubResponse =
        when {
            request.method == "PUT" -> register(request)
            request.path.endsWith(CAN_MODIFY) -> canModify(request)
            else -> StubResponse(404, "{}")
        }

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

    private fun canModify(request: StubRequest): StubResponse {
        val owner = owners[request.path.removeSuffix(CAN_MODIFY)] ?: return ownershipMissing(request.path)
        val actor = request.query.orEmpty().substringAfter("actorId=")

        return StubResponse(200, """{"allowed":${owner == actor}}""")
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

internal fun ownershipMissing(path: String): StubResponse =
    StubResponse(
        status = 404,
        body =
            """
            {"type":"urn:permissions:problem:ownership-not-found","title":"Not Found","status":404,
            "detail":"No ownership is registered for this snippet.","instance":"$path"}
            """.trimIndent(),
        contentType = "application/problem+json",
    )
