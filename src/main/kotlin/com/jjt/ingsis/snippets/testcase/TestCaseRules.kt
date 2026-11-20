package com.jjt.ingsis.snippets.testcase

import java.nio.charset.StandardCharsets

enum class DefinitionRejection {
    MALFORMED,
    TOO_LARGE,
}

object TestCaseRules {
    const val MAXIMUM_ITEMS = 100
    const val MAXIMUM_ITEM_BYTES = 8 * 1024
    const val MAXIMUM_PER_SNIPPET = 50

    fun rejectionOf(definition: TestCaseDefinition): DefinitionRejection? {
        val items = definition.inputs + definition.expectedOutputs
        return when {
            items.any { !isStorable(it) } -> DefinitionRejection.MALFORMED
            definition.inputs.size > MAXIMUM_ITEMS -> DefinitionRejection.TOO_LARGE
            definition.expectedOutputs.size > MAXIMUM_ITEMS -> DefinitionRejection.TOO_LARGE
            items.any { utf8Size(it) > MAXIMUM_ITEM_BYTES } -> DefinitionRejection.TOO_LARGE
            else -> null
        }
    }

    private fun isStorable(text: String): Boolean =
        '\u0000' !in text && StandardCharsets.UTF_8.newEncoder().canEncode(text)

    private fun utf8Size(text: String): Int = text.toByteArray(StandardCharsets.UTF_8).size
}
