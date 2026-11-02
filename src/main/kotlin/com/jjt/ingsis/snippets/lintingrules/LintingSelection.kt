package com.jjt.ingsis.snippets.lintingrules

import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.HexFormat

private const val REVISION_SCHEME = "sha256-v1:"

private val canonicalJson = JsonMapper.builder().build()

data class LintingSelection(
    val rules: Map<String, Any?>,
) {
    val revision: String = REVISION_SCHEME + HexFormat.of().formatHex(digest(rules))

    companion object {
        val EMPTY = LintingSelection(emptyMap())
    }
}

private fun digest(rules: Map<String, Any?>): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(canonicalJson.writeValueAsBytes(rules.toSortedMap()))
