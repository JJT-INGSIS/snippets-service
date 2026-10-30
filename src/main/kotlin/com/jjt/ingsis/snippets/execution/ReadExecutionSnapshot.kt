package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.access.ReadDecision
import com.jjt.ingsis.snippets.access.ReadFailure
import com.jjt.ingsis.snippets.access.ReadGate
import com.jjt.ingsis.snippets.creation.canonicalUuid
import com.jjt.ingsis.snippets.detail.ContentReading
import com.jjt.ingsis.snippets.detail.StableContentRead
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.testcase.TestCase
import com.jjt.ingsis.snippets.testcase.TestCaseStore
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ReadExecutionSnapshot(
    private val gate: ReadGate,
    private val reading: ContentReading,
    private val tests: TestCaseStore,
    private val fingerprints: SnapshotFingerprint,
) {
    fun read(
        snippetId: String,
        testId: String,
        context: ActorContext,
    ): RunResult<ExecutionSnapshot> {
        val id = canonicalUuid(testId) ?: return RunResult.Refused(RunFailure.INVALID_REQUEST)
        return when (val access = gate.open(snippetId, context)) {
            is ReadDecision.Refused -> RunResult.Refused(access.failure.toRunFailure())
            is ReadDecision.Granted -> readWithTest(access.snippet, id)
        }
    }

    fun read(
        snippetId: String,
        context: ActorContext,
    ): RunResult<ExecutionSnapshot> =
        when (val access = gate.open(snippetId, context)) {
            is ReadDecision.Refused -> RunResult.Refused(access.failure.toRunFailure())
            is ReadDecision.Granted -> readWithoutTest(access.snippet)
        }

    private fun readWithTest(
        snippet: LinkedSnippet,
        testId: UUID,
    ): RunResult<ExecutionSnapshot> =
        try {
            // Membership is checked before any blob read or interpreter call.
            val test =
                tests.list(snippet.metadata.id).singleOrNull {
                    it.id == testId &&
                        it.snippetId == snippet.metadata.id
                }
            if (test == null) RunResult.Refused(RunFailure.TEST_MISSING) else capture(snippet, test)
        } catch (_: MetadataPersistenceException) {
            RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
        }

    private fun readWithoutTest(snippet: LinkedSnippet): RunResult<ExecutionSnapshot> =
        try {
            capture(snippet, test = null)
        } catch (_: MetadataPersistenceException) {
            RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
        }

    private fun capture(
        snippet: LinkedSnippet,
        test: TestCase?,
    ): RunResult<ExecutionSnapshot> =
        when (val read = reading.readStable(snippet)) {
            is StableContentRead.Read -> {
                RunResult.Success(snapshotOf(read, test))
            }

            StableContentRead.Conflict -> {
                RunResult.Refused(RunFailure.SNAPSHOT_CONFLICT)
            }

            StableContentRead.Unavailable, StableContentRead.Broken -> {
                RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
            }
        }

    private fun snapshotOf(
        read: StableContentRead.Read,
        test: TestCase?,
    ): ExecutionSnapshot {
        val metadata = read.snippet.metadata
        return ExecutionSnapshot(
            snippetId = metadata.id,
            language = metadata.language,
            version = metadata.version,
            code = read.code,
            contentReference = checkNotNull(read.snippet.reference).value,
            codeHash = fingerprints.code(metadata.language, metadata.version, read.code),
            test = if (test == null) null else testSnapshot(test),
        )
    }

    private fun testSnapshot(test: TestCase): TestSnapshot {
        val definition =
            test.definition.copy(
                inputs = test.definition.inputs.toList(),
                expectedOutputs = test.definition.expectedOutputs.toList(),
            )
        return TestSnapshot(id = test.id, definition = definition, hash = fingerprints.test(definition))
    }
}

internal fun ReadFailure.toRunFailure(): RunFailure =
    when (this) {
        ReadFailure.IDENTITY_REJECTED -> RunFailure.IDENTITY_REQUIRED
        ReadFailure.INVALID_ID -> RunFailure.INVALID_REQUEST
        ReadFailure.SNIPPET_MISSING -> RunFailure.SNIPPET_MISSING
        ReadFailure.PERMISSIONS_UNAVAILABLE, ReadFailure.METADATA_UNAVAILABLE -> RunFailure.DEPENDENCY_UNAVAILABLE
    }
