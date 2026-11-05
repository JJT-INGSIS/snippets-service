package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.formattingrules.FormattingScope
import com.jjt.ingsis.snippets.processing.RuleBatchCandidate
import com.jjt.ingsis.snippets.processing.RuleBatchCandidates
import com.jjt.ingsis.snippets.processing.RuleBatchScope
import com.jjt.ingsis.snippets.reformatting.ReformattingCandidate
import com.jjt.ingsis.snippets.reformatting.ReformattingCandidates
import com.jjt.ingsis.snippets.storage.ContentReference
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

private val BEFORE_FIRST: UUID = UUID(0, 0)

private const val PAGE =
    "SELECT id, content_reference FROM snippets " +
        "WHERE id = ANY (CAST(? AS uuid[])) AND language = ? AND version = ? AND state = 'CONFIRMED' " +
        "AND content_reference IS NOT NULL AND id > ? ORDER BY id LIMIT ?"

@Repository
class JdbcReformattingCandidates(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
) : ReformattingCandidates,
    RuleBatchCandidates {
    override fun page(
        owned: Set<UUID>,
        scope: FormattingScope,
        after: UUID?,
        limit: Int,
    ): List<ReformattingCandidate> =
        page(
            owned = owned,
            scope = RuleBatchScope(scope.ownerId, scope.language, scope.version),
            after = after,
            limit = limit,
        ).map { ReformattingCandidate(it.id, it.reference) }

    override fun page(
        owned: Set<UUID>,
        scope: RuleBatchScope,
        after: UUID?,
        limit: Int,
    ): List<RuleBatchCandidate> =
        transaction.read {
            jdbc.query(
                PAGE,
                { row, _ ->
                    RuleBatchCandidate(
                        id = row.getObject("id", UUID::class.java),
                        reference = ContentReference(row.getString("content_reference")),
                    )
                },
                owned.joinToString(prefix = "{", postfix = "}", separator = ","),
                scope.language,
                scope.version,
                after ?: BEFORE_FIRST,
                limit,
            )
        }
}
