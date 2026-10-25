package com.jjt.ingsis.snippets.http

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private data class Grant(
    val actorId: String,
    val snippetId: UUID,
    val ownerId: String,
)

internal class AccessLedger {
    private val grants = CopyOnWriteArrayList<Grant>()

    fun own(
        ownerId: String,
        snippetId: UUID,
    ) {
        grants.add(Grant(ownerId, snippetId, ownerId))
    }

    fun share(
        ownerId: String,
        snippetId: UUID,
        recipientId: String,
    ) {
        grants.add(Grant(recipientId, snippetId, ownerId))
    }

    fun answer(request: StubRequest): StubResponse {
        if (request.method == "PUT") return registerShare(request)
        val pairs = request.query.orEmpty().split('&')
        val query = pairs.associate { it.substringBefore('=') to decoded(it.substringAfter('=')) }
        val actorId = query.getValue("actorId")

        return if (request.path == "/access") {
            list(actorId, query.getValue("relation"))
        } else if (request.path.endsWith("/can-modify")) {
            val id = UUID.fromString(request.path.split('/')[2])
            val allowed = grants.any { it.snippetId == id && it.ownerId == actorId }
            StubResponse(200, json.writeValueAsString(mapOf("allowed" to allowed)))
        } else {
            single(UUID.fromString(request.path.split('/')[2]), actorId, request.path)
        }
    }

    private fun registerShare(request: StubRequest): StubResponse =
        synchronized(grants) {
            val id = UUID.fromString(request.path.split('/')[2])
            val actor = json.readTree(request.body).get("actorId").asString()
            val recipient = request.path.substringAfter("/shares/")
            val owner =
                grants.find { it.snippetId == id }?.ownerId ?: return@synchronized ownershipMissing(request.path)
            if (owner != actor) {
                val relation = if (grants.any { it.snippetId == id && it.actorId == actor }) "shared" else "none"
                val body =
                    mapOf(
                        "type" to "urn:permissions:problem:share-denied",
                        "status" to 403,
                        "title" to "Forbidden",
                        "detail" to "Only the owner can share this snippet.",
                        "instance" to request.path,
                        "relation" to relation,
                    )
                return@synchronized StubResponse(403, json.writeValueAsString(body), "application/problem+json")
            }
            val existing = grants.any { it.snippetId == id && it.actorId == recipient }
            if (!existing) share(owner, id, recipient)
            StubResponse(
                if (existing) 200 else 201,
                json.writeValueAsString(
                    mapOf(
                        "snippetId" to id.toString(),
                        "recipientId" to recipient,
                    ),
                ),
            )
        }

    private fun single(
        snippetId: UUID,
        actorId: String,
        path: String,
    ): StubResponse {
        val ownerId = grants.find { it.snippetId == snippetId }?.ownerId ?: return ownershipMissing(path)
        val relation =
            when {
                ownerId == actorId -> "owned"
                grants.any { it.snippetId == snippetId && it.actorId == actorId } -> "shared"
                else -> "none"
            }
        val body = mapOf("snippetId" to snippetId.toString(), "ownerId" to ownerId, "relation" to relation)

        return StubResponse(200, json.writeValueAsString(body))
    }

    private fun list(
        actorId: String,
        relation: String,
    ): StubResponse {
        val visible =
            grants
                .filter { it.actorId == actorId }
                .map { grant -> grant to if (grant.ownerId == actorId) "owned" else "shared" }
                .filter { (_, granted) -> relation == "all" || relation == granted }
                .sortedBy { (grant, _) -> grant.snippetId.toString() }
        val snippets =
            visible.map { (grant, granted) ->
                mapOf("snippetId" to grant.snippetId.toString(), "ownerId" to grant.ownerId, "relation" to granted)
            }

        return StubResponse(200, json.writeValueAsString(mapOf("actorId" to actorId, "snippets" to snippets)))
    }

    private fun decoded(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)
}
