package com.jjt.ingsis.snippets.http

import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

@Component
class ExecutionRequestDecoder {
    private val json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()

    fun empty(body: String): Boolean =
        try {
            val node = json.readTree(body)
            node != null && node.isObject && node.propertyNames().none()
        } catch (_: JacksonException) {
            false
        }
}
