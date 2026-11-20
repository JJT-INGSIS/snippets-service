package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.background.NewBackgroundJob
import com.jjt.ingsis.snippets.formattingrules.FormattingRulesStore
import com.jjt.ingsis.snippets.formattingrules.FormattingScope
import com.jjt.ingsis.snippets.formattingrules.FormattingSelection
import com.jjt.ingsis.snippets.formattingrules.RulesReplacement
import com.jjt.ingsis.snippets.formattingrules.SavedFormattingRules
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.util.UUID

private const val FIND =
    "SELECT id, selection FROM formatting_configurations WHERE owner_id = ? AND language = ? AND version = ?"

private const val UPSERT =
    "INSERT INTO formatting_configurations (id, owner_id, language, version, selection, revision, updated_at) " +
        "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, CURRENT_TIMESTAMP) " +
        "ON CONFLICT (owner_id, language, version) DO UPDATE " +
        "SET selection = EXCLUDED.selection, revision = EXCLUDED.revision, updated_at = EXCLUDED.updated_at " +
        "WHERE formatting_configurations.revision <> EXCLUDED.revision " +
        "RETURNING id"

@Repository
class JdbcFormattingRules(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
    private val jobs: JdbcJobRecords,
) : FormattingRulesStore {
    private val json = JsonMapper.builder().build()

    private val selectionType = object : TypeReference<Map<String, Any>>() {}

    override fun find(scope: FormattingScope): SavedFormattingRules? =
        transaction.read {
            jdbc.query(FIND, { row, _ -> saved(row) }, scope.ownerId, scope.language, scope.version).singleOrNull()
        }

    override fun replace(
        scope: FormattingScope,
        selection: FormattingSelection,
        jobFor: (configurationId: UUID) -> NewBackgroundJob,
    ): RulesReplacement = transaction.commit { write(scope, selection, jobFor) }

    private fun write(
        scope: FormattingScope,
        selection: FormattingSelection,
        jobFor: (configurationId: UUID) -> NewBackgroundJob,
    ): RulesReplacement {
        if (selection == FormattingSelection.EMPTY && find(scope) == null) return RulesReplacement.Unchanged
        val configurationId = upsert(scope, selection) ?: return RulesReplacement.Unchanged
        val job = jobFor(configurationId)
        jobs.insert(job)

        return RulesReplacement.Changed(configurationId, job.event.jobId)
    }

    private fun upsert(
        scope: FormattingScope,
        selection: FormattingSelection,
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

    private fun saved(row: ResultSet): SavedFormattingRules =
        SavedFormattingRules(
            configurationId = row.getObject("id", UUID::class.java),
            selection = FormattingSelection(json.readValue(row.getString("selection"), selectionType)),
        )
}
