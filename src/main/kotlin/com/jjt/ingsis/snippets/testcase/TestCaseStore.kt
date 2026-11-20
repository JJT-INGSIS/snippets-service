package com.jjt.ingsis.snippets.testcase

import java.util.UUID

interface TestCaseStore {
    fun create(
        snippetId: UUID,
        definition: TestCaseDefinition,
        maximumPerSnippet: Int,
    ): TestCaseCreation

    fun list(snippetId: UUID): List<TestCase>
}
