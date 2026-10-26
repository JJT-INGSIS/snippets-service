package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.testcase.TestCase
import com.jjt.ingsis.snippets.testcase.TestCaseCreation
import com.jjt.ingsis.snippets.testcase.TestCaseDefinition
import com.jjt.ingsis.snippets.testcase.TestCaseStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.sql.DataSource

private const val GENEROUS_LIMIT = 50

private val tricky =
    TestCaseDefinition(
        inputs = listOf("", "ñandú", "ñandú", "  ", "line\nbreak", "quote \" and \\ backslash", "🌎 e\u0301 עברית"),
        expectedOutputs = listOf("", "", "\t", "<b>&amp;</b>", "{\"json\": [1]}"),
    )

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class)
class TestCasePersistenceTest
    @Autowired
    constructor(
        private val store: TestCaseStore,
        private val jdbc: JdbcTemplate,
    ) {
        @Test
        fun `a definition comes back exactly with order duplicates empty strings and unicode`() {
            val snippet = insertSnippet()

            val created = created(store.create(snippet, tricky, GENEROUS_LIMIT))

            assertEquals(listOf(TestCase(created.id, snippet, tricky)), store.list(snippet))
        }

        @Test
        fun `a definition without inputs or outputs is kept as empty arrays`() {
            val snippet = insertSnippet()
            val empty = TestCaseDefinition(inputs = emptyList(), expectedOutputs = emptyList())

            store.create(snippet, empty, GENEROUS_LIMIT)

            assertEquals(listOf(empty), store.list(snippet).map { it.definition })
        }

        @Test
        fun `listing returns only that snippet in creation order and a real empty list otherwise`() {
            val snippet = insertSnippet()
            val other = insertSnippet()
            val first = created(store.create(snippet, definition("first"), GENEROUS_LIMIT))
            store.create(other, definition("other"), GENEROUS_LIMIT)
            val second = created(store.create(snippet, definition("second"), GENEROUS_LIMIT))

            assertEquals(listOf(first.id, second.id), store.list(snippet).map { it.id })
            assertEquals(emptyList<TestCase>(), store.list(insertSnippet()))
        }

        @Test
        fun `the limit per snippet is never exceeded`() {
            val snippet = insertSnippet()
            repeat(2) { store.create(snippet, definition("kept-$it"), 2) }

            assertEquals(TestCaseCreation.LimitReached, store.create(snippet, definition("rejected"), 2))
            assertEquals(2, store.list(snippet).size)
        }

        @Test
        fun `concurrent creations respect the limit`() {
            val snippet = insertSnippet()
            val pool = Executors.newFixedThreadPool(8)
            val attempts = List(12) { index -> Callable { store.create(snippet, definition("$index"), 5) } }

            val outcomes = pool.invokeAll(attempts).map { it.get() }
            pool.shutdown()

            assertEquals(5, outcomes.count { it is TestCaseCreation.Created })
            assertEquals(5, store.list(snippet).size)
        }

        @Test
        fun `the table only accepts arrays of strings`() {
            val snippet = insertSnippet()
            val invalid = listOf("{\"a\": \"b\"}", "[1]", "[\"ok\", null]", "\"text\"")

            invalid.forEach { inputs ->
                assertThrows(DataAccessException::class.java) {
                    jdbc.update(
                        "INSERT INTO snippet_test_cases (id, snippet_id, inputs, expected_outputs) " +
                            "VALUES (?, ?, CAST(? AS jsonb), '[]')",
                        UUID.randomUUID(),
                        snippet,
                        inputs,
                    )
                }
            }
        }

        @Test
        fun `database failures are never an empty list or a fake creation`() {
            val dataSource = mock(DataSource::class.java) { throw SQLException("Database unavailable") }
            val unavailable =
                JdbcTestCaseStore(JdbcTemplate(dataSource), MetadataTransaction(JdbcTransactionManager(dataSource)))
            val snippet = UUID.randomUUID()

            assertThrows(MetadataPersistenceException::class.java) { unavailable.list(snippet) }
            assertThrows(MetadataPersistenceException::class.java) {
                unavailable.create(snippet, tricky, GENEROUS_LIMIT)
            }
        }

        private fun definition(input: String): TestCaseDefinition = TestCaseDefinition(listOf(input), listOf(input))

        private fun created(creation: TestCaseCreation): TestCase = (creation as TestCaseCreation.Created).testCase

        private fun insertSnippet(): UUID {
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO snippets (id, name, description, language, version, state, creation_key, " +
                    "creation_actor_id, creation_fingerprint) VALUES (?, 'tested', NULL, 'printscript', '1.0', " +
                    "'CONFIRMED', ?, 'dev-juan', 'f')",
                id,
                UUID.randomUUID(),
            )
            return id
        }
    }
