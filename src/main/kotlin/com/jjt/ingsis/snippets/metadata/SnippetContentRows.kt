package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.storage.ContentReference
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.util.UUID

data class LockedSnippet(
    val state: SnippetState,
    val reference: ContentReference?,
)

@Component
class SnippetContentRows(
    private val jdbc: JdbcTemplate,
) {
    fun lock(id: UUID): LockedSnippet? =
        jdbc
            .query(
                "SELECT state, content_reference FROM snippets WHERE id = ? FOR UPDATE",
                { row, _ -> LockedSnippet(SnippetState.valueOf(row.getString("state")), row.reference()) },
                id,
            ).singleOrNull()

    fun findReference(id: UUID): ContentReference? =
        jdbc
            .query("SELECT content_reference FROM snippets WHERE id = ?", { row, _ -> row.reference() }, id)
            .singleOrNull()

    fun setReference(
        id: UUID,
        reference: ContentReference,
    ) {
        jdbc.update("UPDATE snippets SET content_reference = ? WHERE id = ?", reference.value, id)
    }

    fun replace(
        id: UUID,
        details: SnippetDetails,
        reference: ContentReference,
    ) {
        jdbc.update(
            "UPDATE snippets SET name = ?, description = ?, language = ?, version = ?, content_reference = ? " +
                "WHERE id = ?",
            details.name,
            details.description,
            details.language,
            details.version,
            reference.value,
            id,
        )
    }

    private fun ResultSet.reference(): ContentReference? {
        val value = getString("content_reference") ?: return null

        return ContentReference(value)
    }
}
