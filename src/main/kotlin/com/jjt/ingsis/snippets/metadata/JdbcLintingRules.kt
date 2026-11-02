package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.background.NewBackgroundJob
import com.jjt.ingsis.snippets.lintingrules.LintingRulesStore
import com.jjt.ingsis.snippets.lintingrules.LintingScope
import com.jjt.ingsis.snippets.lintingrules.LintingSelection
import com.jjt.ingsis.snippets.lintingrules.RulesReplacement
import com.jjt.ingsis.snippets.lintingrules.SavedLintingRules
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.util.UUID

private const val FIND =
    "SELECT id, selection FROM linting_configurations WHERE owner_id = ? AND language = ? AND version = ?"

private const val UPSERT =
    "INSERT INTO linting_configurations (id, owner_id, language, version, selection, revision, updated_at) " +
        "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, CURRENT_TIMESTAMP) " +
        "ON CONFLICT (owner_id, language, version) DO UPDATE " +
        "SET selection = EXCLUDED.selection, revision = EXCLUDED.revision, updated_at = EXCLUDED.updated_at " +
        "WHERE linting_configurations.revision <> EXCLUDED.revision " +
        "RETURNING id"

@Repository
class JdbcLintingRules(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
    private val jobs: JdbcJobRecords,
) : LintingRulesStore {
    private val json = JsonMapper.builder().build()

    private val selectionType = object : TypeReference<Map<String, Any?>>() {}

    override fun find(scope: LintingScope): SavedLintingRules? =
        transaction.read {
            jdbc.query(FIND, { row, _ -> saved(row) }, scope.ownerId, scope.language, scope.version).singleOrNull()
        }

    override fun replace(
        scope: LintingScope,
        selection: LintingSelection,
        jobFor: (configurationId: UUID) -> NewBackgroundJob,
    ): RulesReplacement = transaction.commit { write(scope, selection, jobFor) }

    private fun write(
        scope: LintingScope,
        selection: LintingSelection,
        jobFor: (configurationId: UUID) -> NewBackgroundJob,
    ): RulesReplacement {
        val configurationId = upsert(scope, selection) ?: return RulesReplacement.Unchanged
        val job = jobFor(configurationId)
        jobs.insert(job)

        return RulesReplacement.Changed(configurationId, job.event.jobId)
    }

    private fun upsert(
        scope: LintingScope,
        selection: LintingSelection,
    ): UUID? =
        jdbc
            .query(
                UPSERT,
                { row, _ -> row.getObject("id", UUID::class.java) },
                UUID.randomUUID(),
                scope.ownerId,
                scope.language,
                scope.version,
                json.writeValueAsString(selection.rules),
                selection.revision,
            ).singleOrNull()

    private fun saved(row: ResultSet): SavedLintingRules =
        SavedLintingRules(
            configurationId = row.getObject("id", UUID::class.java),
            selection = LintingSelection(json.readValue(row.getString("selection"), selectionType)),
        )
}
