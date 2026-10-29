package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.execution.AuditedRun
import com.jjt.ingsis.snippets.execution.CompletedRun
import com.jjt.ingsis.snippets.execution.ExecutionAudit
import com.jjt.ingsis.snippets.execution.ExecutionSnapshot
import com.jjt.ingsis.snippets.language.execution.ExecutionDescriptor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcExecutionAudit(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
) : ExecutionAudit {
    private val json = JsonMapper.builder().findAndAddModules().build()

    override fun reserve(run: AuditedRun): Boolean =
        transaction.commit {
            jdbc.update(
                "INSERT INTO snippet_execution_audit (id, actor_id, snippet_id, test_id, snapshot, started_at) " +
                    "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?) ON CONFLICT DO NOTHING",
                run.id,
                run.actorId,
                run.snapshot.snippetId,
                run.snapshot.testId,
                json.writeValueAsString(run.snapshot),
                Timestamp.from(run.startedAt),
            ) == 1
        }

    override fun bind(
        id: UUID,
        descriptor: ExecutionDescriptor,
    ) = transaction.commit {
        check(
            jdbc.update(
                "UPDATE snippet_execution_audit SET remote = CAST(? AS jsonb) WHERE id = ? AND reserved",
                json.writeValueAsString(descriptor),
                id,
            ) == 1,
        )
    }

    override fun complete(
        id: UUID,
        result: CompletedRun,
    ) = transaction.commit {
        jdbc.update(
            "UPDATE snippet_execution_audit SET result = CAST(? AS jsonb), finished_at = ?, " +
                "failure = NULL, reserved = FALSE WHERE id = ? AND result IS NULL",
            json.writeValueAsString(result),
            Timestamp.from(Instant.now()),
            id,
        )
        Unit
    }

    override fun unknown(
        id: UUID,
        reason: String,
        release: Boolean,
    ) = transaction.commit {
        jdbc.update(
            "UPDATE snippet_execution_audit SET failure = ?, reserved = ?, finished_at = ? " +
                "WHERE id = ? AND result IS NULL AND reserved",
            reason,
            !release,
            if (release) Timestamp.from(Instant.now()) else null,
            id,
        )
        Unit
    }

    override fun reject(
        id: UUID,
        reason: String,
    ) = unknown(id, reason, release = true)

    override fun find(id: UUID): AuditedRun? =
        transaction.read {
            jdbc.query("SELECT * FROM snippet_execution_audit WHERE id = ?", { row, _ -> read(row) }, id).singleOrNull()
        }

    override fun reserved(): List<AuditedRun> =
        transaction.read {
            jdbc.query(
                "SELECT * FROM snippet_execution_audit WHERE reserved ORDER BY started_at",
                { row, _ -> read(row) },
            )
        }

    private fun read(row: ResultSet): AuditedRun =
        AuditedRun(
            id = row.getObject("id", UUID::class.java),
            actorId = row.getString("actor_id"),
            snapshot = decode(row.getString("snapshot"), ExecutionSnapshot::class.java),
            startedAt = row.getTimestamp("started_at").toInstant(),
            remote = row.getString("remote")?.let { decode(it, ExecutionDescriptor::class.java) },
            completed = row.getString("result")?.let { decode(it, CompletedRun::class.java) },
            finishedAt = row.getTimestamp("finished_at")?.toInstant(),
            failure = row.getString("failure"),
            reserved = row.getBoolean("reserved"),
        )

    private fun <T> decode(
        value: String,
        type: Class<T>,
    ): T =
        try {
            json.readValue(value, type)
        } catch (failure: JacksonException) {
            throw MetadataPersistenceException(failure)
        }
}
