package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.creation.canonicalUuid
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

private const val REQUEST_ID = "requestId"
private const val VALUE = "value"

data class RunInput(
    val requestId: UUID,
    val value: String,
)

@Component
class ExecutionRequestDecoder {
    private val json =
        JsonMapper
            .builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()

    fun empty(body: String): Boolean {
        val node = objectOf(body)
        return node != null && node.propertyNames().none()
    }

    fun input(body: String): RunInput? {
        val node = objectOf(body) ?: return null
        val requestId = canonicalUuid(text(node, REQUEST_ID))
        val value = text(node, VALUE)
        val exact = node.propertyNames().toSet() == setOf(REQUEST_ID, VALUE)
        return if (exact && requestId != null && value != null) RunInput(requestId, value) else null
    }

    private fun objectOf(body: String): JsonNode? =
        try {
            val node = json.readTree(body)
            if (node != null && node.isObject) node else null
        } catch (_: JacksonException) {
            null
        }

    private fun text(
        node: JsonNode,
        name: String,
    ): String? {
        val field = node.get(name)
        return if (field != null && field.isString) field.asString() else null
    }
}
