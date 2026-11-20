package com.jjt.ingsis.snippets.lintingrules

import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.HexFormat

private const val REVISION_SCHEME = "sha256-v1:"

private val canonicalJson = JsonMapper.builder().build()

class LintingSelection(
    input: Map<String, Any?>,
) {
    // The provider validates first; false/null are disabled under the published lint reader contract.
    val rules: Map<String, Any?> = input.filterValues { it != null && it != false }.toMap()

    override fun equals(other: Any?): Boolean = other is LintingSelection && rules == other.rules

    override fun hashCode(): Int = rules.hashCode()

    override fun toString(): String = "LintingSelection($rules)"

    val revision: String = REVISION_SCHEME + HexFormat.of().formatHex(digest(rules))

    companion object {
        val EMPTY = LintingSelection(emptyMap())
    }
}

private fun digest(rules: Map<String, Any?>): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(canonicalJson.writeValueAsBytes(rules.toSortedMap()))
