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
            is ReadDecision.Granted -> readAuthorized(access.snippet, id)
        }
    }

    private fun readAuthorized(
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
            if (test == null) {
                RunResult.Refused(RunFailure.TEST_MISSING)
            } else {
                when (val read = reading.readStable(snippet)) {
                    is StableContentRead.Read -> {
                        val metadata = read.snippet.metadata
                        val definition =
                            test.definition.copy(
                                inputs = test.definition.inputs.toList(),
                                expectedOutputs = test.definition.expectedOutputs.toList(),
                            )
                        RunResult.Success(
                            ExecutionSnapshot(
                                snippetId = metadata.id,
                                language = metadata.language,
                                version = metadata.version,
                                code = read.code,
                                contentReference = checkNotNull(read.snippet.reference).value,
                                codeHash = fingerprints.code(metadata.language, metadata.version, read.code),
                                test =
                                    TestSnapshot(
                                        id = test.id,
                                        definition = definition,
                                        hash = fingerprints.test(definition),
                                    ),
                            ),
                        )
                    }

                    StableContentRead.Conflict -> {
                        RunResult.Refused(RunFailure.SNAPSHOT_CONFLICT)
                    }

                    StableContentRead.Unavailable, StableContentRead.Broken -> {
                        RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
                    }
                }
            }
        } catch (_: MetadataPersistenceException) {
            RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
        }
}

internal fun ReadFailure.toRunFailure(): RunFailure =
    when (this) {
        ReadFailure.IDENTITY_REJECTED -> RunFailure.IDENTITY_REQUIRED
        ReadFailure.INVALID_ID -> RunFailure.INVALID_REQUEST
        ReadFailure.SNIPPET_MISSING -> RunFailure.SNIPPET_MISSING
        ReadFailure.PERMISSIONS_UNAVAILABLE, ReadFailure.METADATA_UNAVAILABLE -> RunFailure.DEPENDENCY_UNAVAILABLE
    }
