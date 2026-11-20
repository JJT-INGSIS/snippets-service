package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.lintingrules.LintAssessment
import com.jjt.ingsis.snippets.lintingrules.LintAssessments
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@Repository
class JdbcLintAssessments(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
) : LintAssessments {
    private val json = JsonMapper.builder().findAndAddModules().build()

    override fun record(assessment: LintAssessment) {
        transaction.join {
            // Keep obsolete attempts internally, but never publish them over newer content/configuration.
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO snippet_lint_assessments (id, snippet_id, owner_id, language, version, " +
                    "content_reference, " +
                    "code_hash, rules_revision, processor_revision, state, diagnostics, reason) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)",
                id,
                assessment.snippetId,
                assessment.ownerId,
                assessment.language,
                assessment.version,
                assessment.reference.value,
                assessment.codeHash,
                assessment.rulesRevision,
                assessment.processor,
                assessment.state.name,
                json.writeValueAsString(assessment.diagnostics),
                assessment.reason,
            )
            jdbc.update(
                "UPDATE snippets SET current_lint_assessment = ? WHERE id = ? AND state = 'CONFIRMED' " +
                    "AND content_reference = ? AND language = ? AND version = ? " +
                    "AND NOT EXISTS (SELECT 1 FROM linting_configurations c WHERE c.owner_id = ? " +
                    "AND c.language = ? AND c.version = ? AND c.revision IS DISTINCT FROM ?) " +
                    "AND (CAST(? AS text) IS NOT NULL OR NOT EXISTS (SELECT 1 FROM linting_configurations c WHERE " +
                    "c.owner_id = ? AND c.language = ? AND c.version = ?))",
                id,
                assessment.snippetId,
                assessment.reference.value,
                assessment.language,
                assessment.version,
                assessment.ownerId,
                assessment.language,
                assessment.version,
                assessment.rulesRevision,
                assessment.rulesRevision,
                assessment.ownerId,
                assessment.language,
                assessment.version,
            )
            id
        }
    }
}
