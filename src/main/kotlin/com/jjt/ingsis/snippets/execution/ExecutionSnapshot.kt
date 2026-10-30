package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.testcase.TestCaseDefinition
import java.util.UUID

data class ExecutionSnapshot(
    val snippetId: UUID,
    val testId: UUID,
    val language: String,
    val version: String,
    val code: String,
    val contentReference: String,
    val definition: TestCaseDefinition,
    val codeHash: String,
    val testHash: String,
)

interface SnapshotFingerprint {
    fun code(
        language: String,
        version: String,
        code: String,
    ): String

    fun test(definition: TestCaseDefinition): String
}
