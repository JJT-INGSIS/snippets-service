package com.jjt.ingsis.snippets.testcase

import com.jjt.ingsis.snippets.access.ReadDecision
import com.jjt.ingsis.snippets.access.ReadFailure
import com.jjt.ingsis.snippets.access.ReadGate
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ListTestCases(
    private val gate: ReadGate,
    private val testCases: TestCaseStore,
) {
    fun list(
        snippetId: String?,
        context: ActorContext,
    ): TestCaseListing =
        when (val decision = gate.open(snippetId, context)) {
            is ReadDecision.Granted -> listFor(decision.snippet.metadata.id)
            is ReadDecision.Refused -> TestCaseListing.Failed(decision.failure)
        }

    private fun listFor(snippetId: UUID): TestCaseListing =
        try {
            TestCaseListing.Listed(testCases.list(snippetId))
        } catch (_: MetadataPersistenceException) {
            TestCaseListing.Failed(ReadFailure.METADATA_UNAVAILABLE)
        }
}
