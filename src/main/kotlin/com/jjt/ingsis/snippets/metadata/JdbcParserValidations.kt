package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.revalidation.ParserActivation
import com.jjt.ingsis.snippets.revalidation.ParserScope
import com.jjt.ingsis.snippets.revalidation.ParserValidationSnapshot
import com.jjt.ingsis.snippets.revalidation.ParserValidations
import com.jjt.ingsis.snippets.storage.ContentReference
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.util.UUID

@Repository
class JdbcParserValidations(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
    private val retention: ContentRetention,
) : ParserValidations {
    private val json = JsonMapper.builder().findAndAddModules().build()

    override fun select(
        jobId: UUID,
        scope: ParserScope,
        after: UUID?,
        limit: Int,
    ): List<ParserValidationSnapshot> =
        transaction.requireCommit {
            val page =
                jdbc.query(
                    "SELECT id AS snippet_id, language, version, content_reference AS source_reference " +
                        "FROM snippets WHERE language = ? AND version = ? AND state = 'CONFIRMED' " +
                        "AND content_reference IS NOT NULL " +
                        "AND id > ? ORDER BY id LIMIT ? FOR UPDATE",
                    { row, _ -> snapshot(row) },
                    scope.language,
                    scope.version,
                    after ?: UUID(0, 0),
                    limit,
                )
            page.forEach {
                retention.retain(it.reference)
                jdbc.update(
                    "INSERT INTO parser_validation_snapshots " +
                        "(job_id, snippet_id, source_reference, language, version) " +
                        "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                    jobId,
                    it.snippetId,
                    it.reference.value,
                    it.scope.language,
                    it.scope.version,
                )
            }
            page
        }

    override fun snapshot(
        jobId: UUID,
        snippetId: UUID,
    ): ParserValidationSnapshot? =
        transaction.read {
            jdbc
                .query(
                    "SELECT * FROM parser_validation_snapshots WHERE job_id = ? AND snippet_id = ?",
                    { row, _ -> snapshot(row) },
                    jobId,
                    snippetId,
                ).singleOrNull()
        }

    override fun current(
        snapshot: ParserValidationSnapshot,
        activationId: UUID,
    ): Boolean =
        transaction.read {
            jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM snippets n JOIN parser_activation_scopes a ON " +
                    "a.language = n.language AND a.version = n.version WHERE n.id = ? AND n.state = 'CONFIRMED' " +
                    "AND n.content_reference = ? AND a.language = ? AND a.version = ? AND a.active_activation = ?)",
                Boolean::class.java,
                snapshot.snippetId,
                snapshot.reference.value,
                snapshot.scope.language,
                snapshot.scope.version,
                activationId,
            ) == true
        }

    override fun publish(
        snapshot: ParserValidationSnapshot,
        activation: ParserActivation,
        resultId: UUID,
        valid: Boolean,
        diagnostics: List<Diagnostic>,
    ): Boolean =
        transaction.requireCommit {
            jdbc.queryForList(
                "SELECT active_activation FROM parser_activation_scopes WHERE language = ? AND version = ? FOR SHARE",
                snapshot.scope.language,
                snapshot.scope.version,
            )
            jdbc.queryForList("SELECT id FROM snippets WHERE id = ? FOR UPDATE", snapshot.snippetId)
            if (!current(snapshot, activation.id)) return@requireCommit false
            jdbc.update(
                "INSERT INTO parser_validation_results " +
                    "(snippet_id, result_id, activation_id, source_reference, " +
                    "processor_revision, valid, diagnostics) " +
                    "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb)) ON CONFLICT (snippet_id) DO UPDATE SET " +
                    "result_id = EXCLUDED.result_id, activation_id = EXCLUDED.activation_id, " +
                    "source_reference = EXCLUDED.source_reference, processor_revision = EXCLUDED.processor_revision, " +
                    "valid = EXCLUDED.valid, diagnostics = EXCLUDED.diagnostics, checked_at = clock_timestamp()",
                snapshot.snippetId,
                resultId,
                activation.id,
                snapshot.reference.value,
                activation.processor.value,
                valid,
                json.writeValueAsString(diagnostics),
            )
            true
        }

    private fun snapshot(row: ResultSet): ParserValidationSnapshot =
        ParserValidationSnapshot(
            row.getObject("snippet_id", UUID::class.java),
            ParserScope(row.getString("language"), row.getString("version")),
            ContentReference(row.getString("source_reference")),
        )
}
