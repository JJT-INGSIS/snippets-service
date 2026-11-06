package com.jjt.ingsis.snippets.metadata

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
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
                if (filter.lintBasis != null &&
                    !verified(ids, filter)
                ) {
                    return@execute MetadataPage(0, emptyList(), lintVerified = false)
                }
                val found = rows(ids = ids, filter = filter, order = order, page = page)
                MetadataPage(
                    count(ids, filter),
                    found.map { pair -> pair.first },
                    found.associate { pair ->
                        pair.first.id to
                            pair.second
                    },
                )
            }
        }

    private fun verified(
        ids: Set<UUID>,
        filter: SnippetFilter,
    ): Boolean =
        jdbc
            .query(
                LintProjectionSql.CANDIDATES +
                    "SELECT NOT EXISTS (SELECT 1 FROM candidates WHERE NOT lint_verified)",
                LintQueryParameters.of(ids, filter, includeLintFilter = false),
            ) { row, _ -> row.getBoolean(1) }
            .single()

    private fun count(
        ids: Set<UUID>,
        filter: SnippetFilter,
    ): Long =
        jdbc
            .query(
                LintProjectionSql.CANDIDATES + "SELECT COUNT(*) FROM candidates " + LintProjectionSql.FILTER,
                LintQueryParameters.of(ids, filter),
            ) { row, _ -> row.getLong(1) }
            .single()

    private fun rows(
        ids: Set<UUID>,
        filter: SnippetFilter,
        order: SnippetOrder,
        page: PageRequest,
    ): List<Pair<SnippetMetadata, com.jjt.ingsis.snippets.lintquery.LintFinding>> {
        val sql =
            LintProjectionSql.CANDIDATES + "SELECT * FROM candidates " + LintProjectionSql.FILTER +
                " ORDER BY ${orderBy(order)} LIMIT ? OFFSET ?"
        val setter =
            PreparedStatementSetter { statement ->
                LintQueryParameters.of(ids, filter).setValues(statement)
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
            ) to LintProjectionSql.finding(row)
        }
    }

    private fun orderBy(order: SnippetOrder): String {
        val column =
            when (order.field) {
                SortField.NAME -> "lower(name)"
                SortField.LANGUAGE -> "lower(language)"
                SortField.LINT -> "lint_status"
            }
        val direction =
            when (order.direction) {
                SortDirection.ASC -> "ASC"
                SortDirection.DESC -> "DESC"
            }
        return "$column $direction, id ASC"
    }

    private companion object {
        const val LIMIT_INDEX = 10
        const val OFFSET_INDEX = 11
    }
}
