package com.jjt.ingsis.snippets.metadata

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.PreparedStatement
import java.util.UUID

@Repository
class JdbcSnippetSearch(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val transaction: MetadataTransaction,
) : SnippetSearch {
    private val snapshot =
        TransactionTemplate(transactionManager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            isReadOnly = true
        }

    override fun search(
        ids: Set<UUID>,
        filter: SnippetFilter,
        order: SnippetOrder,
        page: PageRequest,
    ): MetadataPage =
        transaction.read {
            snapshot.execute {
                MetadataPage(count(ids, filter), rows(ids = ids, filter = filter, order = order, page = page))
            }
        }

    private fun count(
        ids: Set<UUID>,
        filter: SnippetFilter,
    ): Long =
        jdbc
            .query("SELECT COUNT(*) FROM snippets $WHERE", conditions(ids, filter)) { row, _ -> row.getLong(1) }
            .single()

    private fun rows(
        ids: Set<UUID>,
        filter: SnippetFilter,
        order: SnippetOrder,
        page: PageRequest,
    ): List<SnippetMetadata> {
        val sql = "SELECT * FROM snippets $WHERE ORDER BY ${orderBy(order)} LIMIT ? OFFSET ?"
        val setter =
            PreparedStatementSetter { statement ->
                conditions(ids, filter).setValues(statement)
                statement.setInt(LIMIT_INDEX, page.size)
                statement.setLong(OFFSET_INDEX, page.offset)
            }
        return jdbc.query(sql, setter) { row, _ ->
            SnippetMetadata(
                id = row.getObject("id", UUID::class.java),
                name = row.getString("name"),
                description = row.getString("description"),
                language = row.getString("language"),
                version = row.getString("version"),
                state = SnippetState.valueOf(row.getString("state")),
            )
        }
    }

    private fun conditions(
        ids: Set<UUID>,
        filter: SnippetFilter,
    ): PreparedStatementSetter =
        PreparedStatementSetter { statement: PreparedStatement ->
            statement.setArray(1, statement.connection.createArrayOf("uuid", ids.toTypedArray()))
            statement.setString(NAME_INDEX, filter.name)
            statement.setString(NAME_INDEX + 1, filter.name)
            statement.setString(LANGUAGE_INDEX, filter.language)
            statement.setString(LANGUAGE_INDEX + 1, filter.language)
        }

    private fun orderBy(order: SnippetOrder): String {
        val column =
            when (order.field) {
                SortField.NAME -> "lower(name)"
                SortField.LANGUAGE -> "lower(language)"
            }
        val direction =
            when (order.direction) {
                SortDirection.ASC -> "ASC"
                SortDirection.DESC -> "DESC"
            }
        return "$column $direction, id ASC"
    }

    private companion object {
        const val WHERE =
            "WHERE id = ANY(?) AND state = 'CONFIRMED' " +
                "AND (? = '' OR strpos(lower(name), lower(?)) > 0) " +
                "AND (? = '' OR language = ?)"
        const val NAME_INDEX = 2
        const val LANGUAGE_INDEX = 4
        const val LIMIT_INDEX = 6
        const val OFFSET_INDEX = 7
    }
}
