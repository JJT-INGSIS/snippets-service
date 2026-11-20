package com.jjt.ingsis.snippets.http

import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

private const val RULES = "rules"

@Component
class LintingRulesRequestDecoder {
    private val mapper =
        JsonMapper
            .builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()

    private val rulesType = object : TypeReference<Map<String, Any?>>() {}

    fun decode(json: String): Map<String, Any?>? =
        try {
            val node = mapper.readTree(json)
            val rules = node?.takeIf { it.isObject && it.propertyNames().toSet() == setOf(RULES) }?.get(RULES)
            if (rules != null && rules.isObject) mapper.convertValue(rules, rulesType) else null
        } catch (_: JacksonException) {
            null
        }
}
