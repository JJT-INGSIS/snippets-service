package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.lintquery.LintFinding
import com.jjt.ingsis.snippets.lintquery.LintStatus
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet

// Shared by list and detail: validity and diagnostic visibility have one definition.
internal object LintProjectionSql {
    private val json = JsonMapper.builder().findAndAddModules().build()
    private val diagnosticType = object : TypeReference<List<Diagnostic>>() {}
    private const val OBSOLETE =
        "a.owner_id IS DISTINCT FROM (x.owners ->> s.id::text) OR " +
            "a.content_reference IS DISTINCT FROM s.content_reference OR a.language <> s.language OR " +
            "a.version <> s.version OR a.rules_revision IS DISTINCT FROM " +
            "COALESCE(c.revision, x.processors -> s.language -> s.version ->> 'defaults') OR " +
            "(a.state IN ('COMPLIANT', 'NON_COMPLIANT') AND a.processor_revision IS NULL) OR " +
            "(a.processor_revision IS NOT NULL AND " +
            "a.processor_revision IS DISTINCT FROM (x.processors -> s.language -> s.version ->> 'processor'))"

    const val CANDIDATES =
        "WITH read_context AS (SELECT CAST(? AS jsonb) AS processors, CAST(? AS jsonb) AS owners), " +
            "candidates AS (SELECT s.*, " +
            "(x.processors -> s.language -> s.version ->> 'processor') IS NOT NULL AS lint_verified, " +
            "a.diagnostics AS lint_diagnostics, a.code_hash AS lint_code_hash, " +
            "a.rules_revision AS lint_rules_revision, a.processor_revision AS lint_processor_revision, " +
            "CASE WHEN a.id IS NULL OR ($OBSOLETE) THEN 'pending' " +
            "WHEN a.state = 'COMPLIANT' THEN 'compliant' WHEN a.state = 'NON_COMPLIANT' THEN 'not-compliant' " +
            "ELSE 'failed' END AS lint_status, " +
            "CASE WHEN a.id IS NULL THEN 'NOT_EVALUATED' WHEN ($OBSOLETE) THEN 'SUPERSEDED' " +
            "ELSE a.reason END AS lint_reason " +
            "FROM snippets s CROSS JOIN read_context x " +
            "LEFT JOIN snippet_lint_assessments a ON a.id = s.current_lint_assessment " +
            "LEFT JOIN linting_configurations c ON c.owner_id = (x.owners ->> s.id::text) " +
            "AND c.language = s.language AND c.version = s.version " +
            "WHERE s.id = ANY(?) AND s.state = 'CONFIRMED' " +
            "AND (? = '' OR strpos(lower(s.name), lower(?)) > 0) AND (? = '' OR s.language = ?)) "

    const val FILTER = "WHERE (CAST(? AS text) IS NULL OR lint_status = ?)"

    fun finding(row: ResultSet): LintFinding {
        val status = LintStatus.entries.single { it.value == row.getString("lint_status") }
        val reason = row.getString("lint_reason")
        return if (status == LintStatus.PENDING) {
            LintFinding(status, reason)
        } else {
            LintFinding(
                status = status,
                reason = reason,
                diagnostics = diagnostics(row),
                codeHash = row.getString("lint_code_hash"),
                rulesRevision = row.getString("lint_rules_revision"),
                processorRevision = row.getString("lint_processor_revision"),
            )
        }
    }

    private fun diagnostics(row: ResultSet): List<Diagnostic> =
        try {
            json.readValue(row.getString("lint_diagnostics"), diagnosticType)
        } catch (failure: JacksonException) {
            throw MetadataPersistenceException(failure)
        }
}
