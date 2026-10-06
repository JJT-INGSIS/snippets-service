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

    private fun tree(json: String): JsonNode? =
        try {
            mapper.readTree(json)?.takeIf { it.isObject }
        } catch (_: JacksonException) {
            null
        }
}
