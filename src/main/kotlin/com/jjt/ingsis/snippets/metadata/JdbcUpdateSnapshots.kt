package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.automatic.UpdateSnapshot
import com.jjt.ingsis.snippets.automatic.UpdateSnapshots
import com.jjt.ingsis.snippets.execution.TestSnapshot
import com.jjt.ingsis.snippets.storage.ContentReference
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@Repository
class JdbcUpdateSnapshots(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
) : UpdateSnapshots {
    private val json = JsonMapper.builder().findAndAddModules().build()
    private val type = object : TypeReference<List<TestSnapshot>>() {}

    override fun find(jobId: UUID): UpdateSnapshot? =
        transaction.read {
            jdbc
                .query("SELECT * FROM snippet_update_snapshots WHERE job_id = ?", { row, _ ->
                    UpdateSnapshot(
                        jobId = jobId,
                        snippetId = row.getObject("snippet_id", UUID::class.java),
                        actorId = row.getString("actor_id"),
                        language = row.getString("language"),
                        version = row.getString("version"),
                        reference = ContentReference(row.getString("content_reference")),
                        codeHash = row.getString("code_hash"),
                        tests = decode(row.getString("tests")),
                        released = row.getTimestamp("released_at") != null,
                    )
                }, jobId)
                .singleOrNull()
        }

    override fun release(jobId: UUID): Boolean =
        transaction.join {
            val terminal =
                jdbc.queryForList(
                    "SELECT id FROM background_jobs WHERE id = ? AND finished_at IS NOT NULL " +
                        "AND state IN ('COMPLETED', 'PARTIAL', 'FAILED', 'SUPERSEDED') FOR UPDATE",
                    jobId,
                )
            if (terminal.isEmpty() || jdbc.queryForObject(
                    "SELECT count(*) FROM execution_permits WHERE job_id = ? AND state <> 'RELEASED'",
                    Int::class.java,
                    jobId,
                ) != 0
            ) {
                false
            } else {
                jdbc.update(
                    "UPDATE snippet_update_snapshots SET released_at = COALESCE(released_at, clock_timestamp()) " +
                        "WHERE job_id = ?",
                    jobId,
                ) == 1
            }
        }

    private fun decode(value: String): List<TestSnapshot> =
        try {
            json.readValue(value, type)
        } catch (failure: JacksonException) {
            throw MetadataPersistenceException(failure)
        }
}
