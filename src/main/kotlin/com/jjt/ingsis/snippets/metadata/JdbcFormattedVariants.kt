package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.formattingrules.FormattedVariant
import com.jjt.ingsis.snippets.formattingrules.FormattedVariants
import com.jjt.ingsis.snippets.formattingrules.FormattingBasis
import com.jjt.ingsis.snippets.formattingrules.FormattingSelection
import com.jjt.ingsis.snippets.formattingrules.VariantPublication
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.storage.ContentReference
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

private const val FIND = "SELECT * FROM formatted_snippets WHERE snippet_id = ?"

private const val LOCK_SNIPPET =
    "SELECT content_reference, language, version FROM snippets " +
        "WHERE id = ? AND state = 'CONFIRMED' AND content_reference IS NOT NULL FOR UPDATE"

private const val CURRENT_RULES =
    "SELECT revision FROM formatting_configurations WHERE owner_id = ? AND language = ? AND version = ? FOR SHARE"

private const val UPSERT =
    "INSERT INTO formatted_snippets (snippet_id, source_reference, language, version, rules_revision, " +
        "processor_revision, derived_reference, job_id, published_at) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
        "ON CONFLICT (snippet_id) DO UPDATE SET source_reference = EXCLUDED.source_reference, " +
        "language = EXCLUDED.language, version = EXCLUDED.version, rules_revision = EXCLUDED.rules_revision, " +
        "processor_revision = EXCLUDED.processor_revision, derived_reference = EXCLUDED.derived_reference, " +
        "job_id = EXCLUDED.job_id, published_at = EXCLUDED.published_at"

@Repository
class JdbcFormattedVariants(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
) : FormattedVariants {
    override fun find(snippetId: UUID): FormattedVariant? =
        transaction.read { jdbc.query(FIND, { row, _ -> variant(row) }, snippetId).singleOrNull() }

    override fun publish(
        variant: FormattedVariant,
        ownerId: String,
        jobId: UUID,
    ): VariantPublication =
        transaction.requireCommit {
            val current = currentBasis(variant, ownerId)

            when {
                current == null -> VariantPublication.Superseded
                variant.basis.isCurrent(current) -> VariantPublication.Published(replace(variant, jobId))
                onlyCodeChanged(variant.basis, current) -> VariantPublication.CodeChanged
                else -> VariantPublication.Superseded
            }
        }

    private fun onlyCodeChanged(
        used: FormattingBasis,
        current: FormattingBasis,
    ): Boolean = used.isCurrent(current.copy(content = used.content))

    private fun currentBasis(
        variant: FormattedVariant,
        ownerId: String,
    ): FormattingBasis? =
        jdbc
            .query(
                LOCK_SNIPPET,
                { row, _ ->
                    FormattingBasis(
                        content = ContentReference(row.getString("content_reference")),
                        language = row.getString("language"),
                        version = row.getString("version"),
                        rules = rulesRevision(ownerId, row.getString("language"), row.getString("version")),
                        processor = variant.basis.processor,
                    )
                },
                variant.snippetId,
            ).singleOrNull()

    private fun rulesRevision(
        ownerId: String,
        language: String,
        version: String,
    ): String =
        jdbc
            .queryForList(CURRENT_RULES, String::class.java, ownerId, language, version)
            .singleOrNull() ?: FormattingSelection.EMPTY.revision

    private fun replace(
        variant: FormattedVariant,
        jobId: UUID,
    ): ContentReference? {
        val previous = find(variant.snippetId)?.derived
        jdbc.update(
            UPSERT,
            variant.snippetId,
            variant.basis.content.value,
            variant.basis.language,
            variant.basis.version,
            variant.basis.rules,
            variant.basis.processor.value,
            variant.derived.value,
            jobId,
        )
        return previous?.takeIf { it != variant.derived }
    }

    private fun variant(row: ResultSet): FormattedVariant =
        FormattedVariant(
            snippetId = row.getObject("snippet_id", UUID::class.java),
            basis =
                FormattingBasis(
                    content = ContentReference(row.getString("source_reference")),
                    language = row.getString("language"),
                    version = row.getString("version"),
                    rules = row.getString("rules_revision"),
                    processor = ProcessorRevision(row.getString("processor_revision")),
                ),
            derived = ContentReference(row.getString("derived_reference")),
        )
}
