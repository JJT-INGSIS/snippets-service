package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.language.execution.ExecutionMode
import com.jjt.ingsis.snippets.language.execution.ExecutionRequest
import com.jjt.ingsis.snippets.testcase.TestCaseDefinition
import java.util.UUID

data class ExecutionSnapshot(
    val snippetId: UUID,
    val language: String,
    val version: String,
    val code: String,
    val contentReference: String,
    val codeHash: String,
    val test: TestSnapshot?,
) {
    fun mode(): ExecutionMode = if (test == null) ExecutionMode.INTERACTIVE else ExecutionMode.TEST

    fun request(): ExecutionRequest =
        ExecutionRequest(code = code, version = version, mode = mode(), inputs = test?.definition?.inputs.orEmpty())
}

data class TestSnapshot(
    val id: UUID,
    val definition: TestCaseDefinition,
    val hash: String,
)

interface SnapshotFingerprint {
    fun code(
        language: String,
        version: String,
        code: String,
    ): String

    fun test(definition: TestCaseDefinition): String
}
