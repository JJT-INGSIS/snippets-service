package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.lintquery.DetailLint
import com.jjt.ingsis.snippets.lintquery.LintDetailResults
import com.jjt.ingsis.snippets.lintquery.LintFinding
import com.jjt.ingsis.snippets.lintquery.LintReadBasis
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcLintDetail(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
) : LintDetailResults {
    override fun find(
        linked: LinkedSnippet,
        basis: LintReadBasis,
    ): DetailLint =
        transaction.read {
            jdbc
                .query(
                    LintProjectionSql.CANDIDATES + "SELECT * FROM candidates " + LintProjectionSql.FILTER,
                    LintQueryParameters.of(setOf(linked.metadata.id), SnippetFilter("", "", lintBasis = basis)),
                ) { row, _ ->
                    when {
                        !row.getBoolean("lint_verified") -> DetailLint.Unavailable

                        row.getString(
                            "content_reference",
                        ) != linked.reference?.value -> DetailLint.Found(LintFinding.SUPERSEDED)

                        else -> DetailLint.Found(LintProjectionSql.finding(row))
                    }
                }.singleOrNull() ?: DetailLint.Found(LintFinding.SUPERSEDED)
        }
}
