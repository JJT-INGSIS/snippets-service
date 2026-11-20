package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.lintquery.LanguageVersion
import com.jjt.ingsis.snippets.lintquery.MetadataLanguages
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.stereotype.Repository
import java.util.UUID

private const val LANGUAGE_INDEX = 4

@Repository
class JdbcMetadataLanguages(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
) : MetadataLanguages {
    override fun find(
        ids: Set<UUID>,
        filter: SnippetFilter,
    ): Set<LanguageVersion> =
        transaction.read {
            if (ids.isEmpty()) {
                emptySet()
            } else {
                jdbc
                    .query(
                        "SELECT DISTINCT language, version FROM snippets WHERE state = 'CONFIRMED' AND id = ANY(?) " +
                            "AND (? = '' OR strpos(lower(name), lower(?)) > 0) AND (? = '' OR language = ?)",
                        PreparedStatementSetter { statement ->
                            statement.setArray(1, statement.connection.createArrayOf("uuid", ids.toTypedArray()))
                            statement.setString(2, filter.name)
                            statement.setString(2 + 1, filter.name)
                            statement.setString(LANGUAGE_INDEX, filter.language)
                            statement.setString(LANGUAGE_INDEX + 1, filter.language)
                        },
                    ) { row, _ -> LanguageVersion(row.getString(1), row.getString(2)) }
                    .toSet()
            }
        }
}
