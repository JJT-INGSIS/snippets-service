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

private const val INSERT =
    "INSERT INTO linting_configurations (id, owner_id, language, version, selection, revision, updated_at) " +
        "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, CURRENT_TIMESTAMP) " +
        "ON CONFLICT (owner_id, language, version) DO NOTHING RETURNING id"

private const val UPDATE =
    "UPDATE linting_configurations SET selection = CAST(? AS jsonb), revision = ?, updated_at = CURRENT_TIMESTAMP " +
        "WHERE owner_id = ? AND language = ? AND version = ? AND revision <> ? RETURNING id"

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
        implicitSelection: LintingSelection?,
        jobFor: (configurationId: UUID) -> NewBackgroundJob,
    ): RulesReplacement =
        transaction.commit {
            write(scope = scope, selection = selection, implicitSelection = implicitSelection, jobFor = jobFor)
        }

    private fun write(
        scope: LintingScope,
        selection: LintingSelection,
        implicitSelection: LintingSelection?,
        jobFor: (configurationId: UUID) -> NewBackgroundJob,
    ): RulesReplacement {
        val inserted = insert(scope, selection)
        if (inserted != null && selection == implicitSelection) return RulesReplacement.Unchanged
        val configurationId = inserted ?: update(scope, selection) ?: return RulesReplacement.Unchanged
        val job = jobFor(configurationId)
        jobs.insert(job)

        return RulesReplacement.Changed(configurationId, job.event.jobId)
    }

    private fun insert(
        scope: LintingScope,
        selection: LintingSelection,
    ): UUID? =
        jdbc
            .query(
                INSERT,
                { row, _ -> row.getObject("id", UUID::class.java) },
                UUID.randomUUID(),
                scope.ownerId,
                scope.language,
                scope.version,
                json.writeValueAsString(selection.rules),
                selection.revision,
            ).singleOrNull()

    private fun update(
        scope: LintingScope,
        selection: LintingSelection,
    ): UUID? =
        jdbc
            .query(
                UPDATE,
                { row, _ -> row.getObject("id", UUID::class.java) },
                json.writeValueAsString(selection.rules),
                selection.revision,
                scope.ownerId,
                scope.language,
                scope.version,
                selection.revision,
            ).singleOrNull()

    private fun saved(row: ResultSet): SavedLintingRules =
        SavedLintingRules(
            configurationId = row.getObject("id", UUID::class.java),
            selection = LintingSelection(json.readValue(row.getString("selection"), selectionType)),
        )
}
