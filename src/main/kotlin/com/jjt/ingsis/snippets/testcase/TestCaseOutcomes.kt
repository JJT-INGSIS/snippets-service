package com.jjt.ingsis.snippets.testcase

import com.jjt.ingsis.snippets.access.ReadFailure

sealed interface TestCaseAddition {
    data class Created(
        val testCase: TestCase,
    ) : TestCaseAddition

    data class Invalid(
        val rejection: DefinitionRejection,
    ) : TestCaseAddition

    data object LimitReached : TestCaseAddition

    data object NotOwner : TestCaseAddition

    data class Failed(
        val failure: ReadFailure,
    ) : TestCaseAddition
}

sealed interface TestCaseListing {
    data class Listed(
        val testCases: List<TestCase>,
    ) : TestCaseListing

    data class Failed(
        val failure: ReadFailure,
    ) : TestCaseListing
}
