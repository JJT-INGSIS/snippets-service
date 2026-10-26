package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.testcase.TestCase
import java.util.UUID

data class TestCaseResponse(
    val id: UUID,
    val snippetId: UUID,
    val inputs: List<String>,
    val expectedOutputs: List<String>,
) {
    companion object {
        fun from(testCase: TestCase): TestCaseResponse =
            TestCaseResponse(
                id = testCase.id,
                snippetId = testCase.snippetId,
                inputs = testCase.definition.inputs,
                expectedOutputs = testCase.definition.expectedOutputs,
            )
    }
}

data class TestCaseListResponse(
    val items: List<TestCaseResponse>,
)
