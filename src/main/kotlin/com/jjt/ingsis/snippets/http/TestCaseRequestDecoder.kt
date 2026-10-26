package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.testcase.TestCaseDefinition
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

private const val INPUTS = "inputs"
private const val EXPECTED_OUTPUTS = "expectedOutputs"

@Component
class TestCaseRequestDecoder {
    private val mapper =
        JsonMapper
            .builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()

    fun decode(json: String): TestCaseDefinition? =
        try {
            val node = mapper.readTree(json)
            if (node != null && node.isObject && node.propertyNames().toSet() == setOf(INPUTS, EXPECTED_OUTPUTS)) {
                definitionOf(node.get(INPUTS), node.get(EXPECTED_OUTPUTS))
            } else {
                null
            }
        } catch (_: JacksonException) {
            null
        }

    private fun definitionOf(
        inputs: JsonNode,
        expectedOutputs: JsonNode,
    ): TestCaseDefinition? {
        val inputTexts = textsOf(inputs) ?: return null
        val expectedTexts = textsOf(expectedOutputs) ?: return null
        return TestCaseDefinition(inputTexts, expectedTexts)
    }

    private fun textsOf(node: JsonNode): List<String>? {
        val items = node.values()
        return if (node.isArray && items.all { it.isString }) items.map { it.asString() } else null
    }
}
