package com.jjt.ingsis.snippets.testcase

import java.util.UUID

data class TestCaseDefinition(
    val inputs: List<String>,
    val expectedOutputs: List<String>,
)

data class TestCase(
    val id: UUID,
    val snippetId: UUID,
    val definition: TestCaseDefinition,
)

sealed interface TestCaseCreation {
    data class Created(
        val testCase: TestCase,
    ) : TestCaseCreation

    data object LimitReached : TestCaseCreation
}
