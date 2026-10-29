package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.language.execution.ExecutionDescriptor
import com.jjt.ingsis.snippets.language.execution.ExecutionMode
import com.jjt.ingsis.snippets.language.execution.ExecutionState
import com.jjt.ingsis.snippets.metadata.JsonSnapshotFingerprint
import com.jjt.ingsis.snippets.testcase.TestCaseDefinition
import java.util.UUID

internal fun snapshot(
    expected: List<String> = listOf("", "ñ", "ñ"),
    snippetId: UUID = UUID.randomUUID(),
    testId: UUID = UUID.randomUUID(),
): ExecutionSnapshot {
    val definition = TestCaseDefinition(listOf("", "ñ", "ñ"), expected)
    val hash = JsonSnapshotFingerprint()
    return ExecutionSnapshot(
        snippetId = snippetId,
        testId = testId,
        language = "printscript",
        version = "1.1",
        code = "println(\"ñ\");",
        contentReference = "stored-reference",
        definition = definition,
        codeHash = hash.code("printscript", "1.1", "println(\"ñ\");"),
        testHash = hash.test(definition),
    )
}

internal fun remote(
    id: UUID = UUID.randomUUID(),
    lastSeq: Long = 1,
): ExecutionDescriptor =
    ExecutionDescriptor(
        id = id,
        mode = ExecutionMode.TEST,
        state = ExecutionState.RUNNING,
        limits = limits,
        lastSeq = lastSeq,
    )

internal val limits =
    mapOf(
        "activeGlobal" to 4L,
        "totalSeconds" to 120L,
        "inputWaitSeconds" to 30L,
        "reconnectSeconds" to 15L,
        "codeBytes" to 1048576L,
        "inputBytes" to 8192L,
        "outputBytes" to 1048576L,
        "bufferEvents" to 256L,
        "bufferBytes" to 262144L,
        "terminalRetentionSeconds" to 60L,
    )
