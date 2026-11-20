package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.testcase.TestCase
import com.jjt.ingsis.snippets.testcase.TestCaseCreation
import com.jjt.ingsis.snippets.testcase.TestCaseDefinition
import com.jjt.ingsis.snippets.testcase.TestCaseStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.util.UUID

private val stringList = object : TypeReference<List<String>>() {}

@Repository
class JdbcTestCaseStore(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
) : TestCaseStore {
    private val json = JsonMapper.builder().build()

    override fun create(
        snippetId: UUID,
        definition: TestCaseDefinition,
        maximumPerSnippet: Int,
    ): TestCaseCreation =
        transaction.commit {
            jdbc.queryForList("SELECT id FROM snippets WHERE id = ? FOR UPDATE", snippetId)
            if (count(snippetId) >= maximumPerSnippet) {
                TestCaseCreation.LimitReached
            } else {
                TestCaseCreation.Created(insert(snippetId, definition))
            }
        }

    override fun list(snippetId: UUID): List<TestCase> =
        transaction.read {
            jdbc.query(
                "SELECT id, snippet_id, inputs::text AS inputs, expected_outputs::text AS expected_outputs " +
                    "FROM snippet_test_cases WHERE snippet_id = ? ORDER BY creation_order",
                { row, _ -> row.toTestCase() },
                snippetId,
            )
        }

    private fun count(snippetId: UUID): Long =
        jdbc
            .query(
                "SELECT COUNT(*) FROM snippet_test_cases WHERE snippet_id = ?",
                { row, _ -> row.getLong(1) },
                snippetId,
            ).single()

    private fun insert(
        snippetId: UUID,
        definition: TestCaseDefinition,
    ): TestCase {
        val testCase = TestCase(UUID.randomUUID(), snippetId, definition)
        jdbc.update(
            "INSERT INTO snippet_test_cases (id, snippet_id, inputs, expected_outputs) " +
                "VALUES (?, ?, CAST(? AS jsonb), CAST(? AS jsonb))",
            testCase.id,
            snippetId,
            json.writeValueAsString(definition.inputs),
            json.writeValueAsString(definition.expectedOutputs),
        )
        return testCase
    }

    private fun ResultSet.toTestCase(): TestCase =
        TestCase(
            id = getObject("id", UUID::class.java),
            snippetId = getObject("snippet_id", UUID::class.java),
            definition =
                TestCaseDefinition(
                    inputs = json.readValue(getString("inputs"), stringList),
                    expectedOutputs = json.readValue(getString("expected_outputs"), stringList),
                ),
        )
}
