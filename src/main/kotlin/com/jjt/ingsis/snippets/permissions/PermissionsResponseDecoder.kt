package com.jjt.ingsis.snippets.permissions

import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@Component
class PermissionsResponseDecoder {
    private val mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()

    internal fun ownership(
        response: PermissionsResponse,
        snippetId: UUID,
    ): Ownership? {
        if (response.contentType != "application/json") return null
        val body = tree(response.body) ?: return null
        val id = body.get("snippetId")
        val owner = body.get("ownerId")
        if (id?.isString != true || owner?.isString != true || owner.asString().isBlank()) return null
        return if (id.asString() == snippetId.toString()) Ownership(snippetId, owner.asString()) else null
    }

    internal fun problem(
        response: PermissionsResponse,
        path: String,
        category: String,
    ): Boolean {
        if (response.contentType != "application/problem+json") return false
        val body = tree(response.body) ?: return false
        return body.get("status")?.isIntegralNumber == true &&
            body.get("status").toString() == response.status.toString() &&
            body.get("type")?.asString("") == "urn:permissions:problem:$category" &&
            body.get("instance")?.asString("") == path &&
            listOf("title", "detail").all { body.get(it)?.isString == true && body.get(it).asString().isNotBlank() }
    }

    internal fun allowed(response: PermissionsResponse): Boolean? {
        if (response.contentType != "application/json") return null
        val body = tree(response.body) ?: return null
        val allowed = body.get("allowed") ?: return null
        return if (allowed.isBoolean) allowed.booleanValue() else null
    }

    internal fun accesses(
        response: PermissionsResponse,
        actorId: String,
    ): List<SnippetAccess>? {
        if (response.contentType != "application/json") return null
        val body = tree(response.body) ?: return null
        val items = body.get("snippets")
        if (text(body, "actorId") != actorId || items?.isArray != true) return null
        val accesses = items.values().mapNotNull(::access)
        val valid = accesses.size == items.size() && accesses.distinctBy { it.snippetId }.size == accesses.size
        return if (valid) accesses else null
    }

    internal fun accessTo(
        response: PermissionsResponse,
        snippetId: UUID,
    ): AccessLookup? {
        if (response.contentType != "application/json") return null
        val body = tree(response.body) ?: return null
        val owner = text(body, "ownerId")
        val relation = text(body, "relation")
        val valid = text(body, "snippetId") == snippetId.toString() && !owner.isNullOrBlank()
        return when {
            !valid -> null
            relation == "none" -> AccessLookup.Denied
            else -> access(body)?.let(AccessLookup::Granted)
        }
    }

    internal fun confirmsShare(
        response: PermissionsResponse,
        snippetId: UUID,
        recipientId: String,
    ): Boolean {
        if (response.contentType != "application/json") return false
        val body = tree(response.body) ?: return false
        return text(body, "snippetId") == snippetId.toString() && text(body, "recipientId") == recipientId
    }

    internal fun shareDenial(
        response: PermissionsResponse,
        path: String,
    ): ShareRegistration? {
        if (!problem(response, path, "share-denied")) return null
        return when (text(tree(response.body) ?: return null, "relation")) {
            "shared" -> ShareRegistration.Denied
            "none" -> ShareRegistration.Missing
            else -> null
        }
    }

    private fun access(item: JsonNode): SnippetAccess? {
        val snippetId = text(item, "snippetId")?.let(::canonicalUuid)
        val owner = text(item, "ownerId")
        val relation = AccessRelation.entries.find { it.name.lowercase() == text(item, "relation") }
        return if (snippetId == null || owner.isNullOrBlank() || relation == null) {
            null
        } else {
            SnippetAccess(snippetId, owner, relation)
        }
    }

    private fun text(
        node: JsonNode,
        field: String,
    ): String? = node.get(field)?.takeIf { it.isString }?.asString()

    private fun canonicalUuid(value: String): UUID? =
        try {
            UUID.fromString(value).takeIf { it.toString() == value }
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun tree(json: String): JsonNode? =
        try {
            mapper.readTree(json)?.takeIf { it.isObject }
        } catch (_: JacksonException) {
            null
        }
}
