package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.execution.SnapshotFingerprint
import com.jjt.ingsis.snippets.testcase.TestCaseDefinition
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.HexFormat

@Component
class JsonSnapshotFingerprint : SnapshotFingerprint {
    private val json = JsonMapper.builder().build()

    override fun code(
        language: String,
        version: String,
        code: String,
    ): String = hash(listOf(language, version, code))

    override fun test(definition: TestCaseDefinition): String =
        hash(listOf(definition.inputs, definition.expectedOutputs))

    private fun hash(value: Any): String =
        "sha256-v1:" +
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)))
}
