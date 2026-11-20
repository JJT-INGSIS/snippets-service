package com.jjt.ingsis.snippets.testcase

import com.jjt.ingsis.snippets.access.ReadDecision
import com.jjt.ingsis.snippets.access.ReadFailure
import com.jjt.ingsis.snippets.access.ReadGate
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.permissions.AccessRelation
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class CreateTestCase(
    private val gate: ReadGate,
    private val testCases: TestCaseStore,
) {
    fun create(
        snippetId: String?,
        definition: TestCaseDefinition,
        context: ActorContext,
    ): TestCaseAddition {
        val rejection = TestCaseRules.rejectionOf(definition)
        if (rejection != null) return TestCaseAddition.Invalid(rejection)

        return when (val decision = gate.open(snippetId, context)) {
            is ReadDecision.Granted -> addAsOwner(decision, definition)
            is ReadDecision.Refused -> TestCaseAddition.Failed(decision.failure)
        }
    }

    private fun addAsOwner(
        decision: ReadDecision.Granted,
        definition: TestCaseDefinition,
    ): TestCaseAddition =
        if (decision.access.relation == AccessRelation.OWNED) {
            save(decision.snippet.metadata.id, definition)
        } else {
            TestCaseAddition.NotOwner
        }

    private fun save(
        snippetId: UUID,
        definition: TestCaseDefinition,
    ): TestCaseAddition =
        try {
            when (val creation = testCases.create(snippetId, definition, TestCaseRules.MAXIMUM_PER_SNIPPET)) {
                is TestCaseCreation.Created -> TestCaseAddition.Created(creation.testCase)
                TestCaseCreation.LimitReached -> TestCaseAddition.LimitReached
            }
        } catch (_: MetadataPersistenceException) {
            TestCaseAddition.Failed(ReadFailure.METADATA_UNAVAILABLE)
        }
}
