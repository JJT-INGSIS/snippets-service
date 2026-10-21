package com.jjt.ingsis.snippets.storage.asset

import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

private const val FORMAT = "snippet-content-v1"
private const val MAX_JSON_ESCAPE_BYTES = 6

internal object AssetEnvelope {
    private val mapper =
        JsonMapper
            .builder(
                JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build(),
            ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()
    private val overhead = mapper.writeValueAsBytes(linkedMapOf("format" to FORMAT, "code" to "")).size

    // A control byte can become six JSON bytes (for example, U+0000 becomes \u0000).
    fun maxWireBytes(contentLimit: Int): Int {
        val bound = contentLimit.toLong() * MAX_JSON_ESCAPE_BYTES + overhead
        require(contentLimit > 0 && bound < Int.MAX_VALUE) { "Asset content limit is outside the supported range" }
        return bound.toInt()
    }

    fun encode(
        code: String,
        contentLimit: Int,
    ): ByteArray? {
        if (AssetText.encode(code, contentLimit) == null) return null
        return try {
            mapper
                .writeValueAsBytes(linkedMapOf("format" to FORMAT, "code" to code))
                .takeIf { it.size <= maxWireBytes(contentLimit) }
        } catch (_: JacksonException) {
            null
        }
    }

    fun decode(
        bytes: ByteArray,
        contentLimit: Int,
    ): String? {
        if (bytes.size > maxWireBytes(contentLimit)) return null
        val json = AssetText.decode(bytes) ?: return null
        return try {
            val node = mapper.readTree(json)
            val format = node?.get("format")
            val code = node?.get("code")
            when {
                node?.isObject != true -> null
                node.propertyNames().toSet() != setOf("format", "code") -> null
                format?.isString != true || format.asString() != FORMAT -> null
                code?.isString != true -> null
                else -> code.asString().takeIf { AssetText.encode(it, contentLimit) != null }
            }
        } catch (_: JacksonException) {
            null
        }
    }
}
