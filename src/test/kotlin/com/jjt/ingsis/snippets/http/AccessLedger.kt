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
        val pairs = request.query.orEmpty().split('&')
        val query = pairs.associate { it.substringBefore('=') to decoded(it.substringAfter('=')) }
        val actorId = query.getValue("actorId")
        val relation = query.getValue("relation")
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
