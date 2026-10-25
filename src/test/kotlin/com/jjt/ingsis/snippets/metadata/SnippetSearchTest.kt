package com.jjt.ingsis.snippets.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

private val anyName = SnippetFilter(name = "", language = "")
private val byName = SnippetOrder(SortField.NAME, SortDirection.ASC)

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class)
class SnippetSearchTest
    @Autowired
    constructor(
        private val search: SnippetSearch,
        private val jdbc: JdbcTemplate,
    ) {
        @Test
        fun `only confirmed snippets inside the authorized ids are counted and listed`() {
            val visible = insert("Alpha")
            val pending = insert("Beta", state = SnippetState.PENDING)
            insert("Gamma")

            val found = find(setOf(visible, pending, UUID.randomUUID()))

            assertEquals(1, found.count)
            assertEquals(listOf(visible), found.snippets.map { it.id })
        }

        @Test
        fun `name filter is a case insensitive substring and wildcards are literal`() {
            val ids =
                setOf(
                    insert("Hello World"),
                    insert("say HELLO"),
                    insert("100% done"),
                    insert("1000 done"),
                    insert("snake_case"),
                    insert("snakeXcase"),
                )

            assertEquals(listOf("Hello World", "say HELLO"), names(ids, SnippetFilter("hello", "")))
            assertEquals(listOf("100% done"), names(ids, SnippetFilter("0%", "")))
            assertEquals(listOf("snake_case"), names(ids, SnippetFilter("e_c", "")))
        }

        @Test
        fun `language filter is exact`() {
            val ids = setOf(insert("A", language = "printscript"), insert("B", language = "PrintScript"))

            assertEquals(listOf("A"), names(ids, SnippetFilter("", "printscript")))
            assertEquals(emptyList<String>(), names(ids, SnippetFilter("", "print")))
        }

        @Test
        fun `order is global stable and ties are broken by id`() {
            val twins = List(3) { insert("same") }
            val ids = twins.toSet() + insert("apple") + insert("Zebra")

            val ascending = find(ids).snippets
            val descending =
                find(ids, order = SnippetOrder(SortField.NAME, SortDirection.DESC)).snippets

            assertEquals(listOf("apple", "same", "same", "same", "Zebra"), ascending.map { it.name })
            assertEquals(twins.sortedBy { it.toString() }, ascending.filter { it.name == "same" }.map { it.id })
            assertEquals(listOf("Zebra", "same", "same", "same", "apple"), descending.map { it.name })
            assertEquals(twins.sortedBy { it.toString() }, descending.filter { it.name == "same" }.map { it.id })
        }

        @Test
        fun `language can be the sort field`() {
            val ids = setOf(insert("one", language = "python"), insert("two", language = "Java"))

            val sorted =
                find(ids, order = SnippetOrder(SortField.LANGUAGE, SortDirection.ASC))

            assertEquals(listOf("Java", "python"), sorted.snippets.map { it.language })
        }

        @Test
        fun `pages split the same ordered result and keep the total count`() {
            val ids = List(7) { index -> insert("item-$index") }.toSet()
            val all = find(ids, page = PageRequest(0, 100)).snippets

            val pages = (0..4).map { number -> find(ids, page = PageRequest(number, 2)) }

            assertEquals(List(5) { 7L }, pages.map { it.count })
            assertEquals(all, pages.flatMap { it.snippets })
            assertEquals(emptyList<SnippetMetadata>(), pages.last().snippets)
        }

        @Test
        fun `no authorized ids means an empty page with zero count`() {
            assertEquals(MetadataPage(0, emptyList()), find(emptySet()))
        }

        @Test
        fun `database failure is not an empty page`() {
            val dataSource = mock(DataSource::class.java) { throw SQLException("Database unavailable") }
            val unavailable =
                JdbcSnippetSearch(
                    JdbcTemplate(dataSource),
                    JdbcTransactionManager(dataSource),
                    MetadataTransaction(JdbcTransactionManager(dataSource)),
                )

            assertThrows(MetadataPersistenceException::class.java) {
                unavailable.search(
                    ids = setOf(UUID.randomUUID()),
                    filter = anyName,
                    order = byName,
                    page = PageRequest(0, 10),
                )
            }
        }

        private fun names(
            ids: Set<UUID>,
            filter: SnippetFilter,
        ): List<String> = find(ids, filter = filter).snippets.map { it.name }

        private fun find(
            ids: Set<UUID>,
            filter: SnippetFilter = anyName,
            order: SnippetOrder = byName,
            page: PageRequest = PageRequest(0, 10),
        ): MetadataPage = search.search(ids = ids, filter = filter, order = order, page = page)

        private fun insert(
            name: String,
            language: String = "printscript",
            state: SnippetState = SnippetState.CONFIRMED,
        ): UUID {
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO snippets (id, name, description, language, version, state, creation_key, " +
                    "creation_actor_id, creation_fingerprint) VALUES (?, ?, NULL, ?, '1.0', ?, ?, 'dev-juan', 'f')",
                id,
                name,
                language,
                state.name,
                UUID.randomUUID(),
            )
            return id
        }
    }
