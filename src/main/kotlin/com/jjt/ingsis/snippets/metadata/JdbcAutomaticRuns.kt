package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.automatic.AutomaticRuns
import com.jjt.ingsis.snippets.background.RemotePermit
import com.jjt.ingsis.snippets.execution.AuditedRun
import com.jjt.ingsis.snippets.execution.CompletedRun
import com.jjt.ingsis.snippets.execution.ExecutionSnapshot
import com.jjt.ingsis.snippets.language.execution.ExecutionDescriptor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

private const val MAX_AUTOMATIC_REASON_LENGTH = 500

@Repository
class JdbcAutomaticRuns(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
) : AutomaticRuns {
    override fun begin(
        permit: RemotePermit,
        actorId: String,
        snapshot: ExecutionSnapshot,
    ): AuditedRun =
        transaction.requireCommit {
            jdbc.update(
                "INSERT INTO snippet_execution_audit (id, actor_id, snippet_id, test_id, snapshot, started_at, " +
                    "reserved, mode, background_permit_id) VALUES (?, ?, ?, ?, CAST(? AS jsonb), " +
                    "clock_timestamp(), FALSE, 'TEST', ?) ON CONFLICT (id) DO NOTHING",
                permit.id,
                actorId,
                snapshot.snippetId,
                checkNotNull(snapshot.test).id,
                writeAuditJson(snapshot),
                permit.id,
            )
            checkNotNull(find(permit.id))
        }

    override fun find(permitId: UUID): AuditedRun? =
        transaction.read {
            jdbc
                .query(
                    "SELECT * FROM snippet_execution_audit WHERE id = ? AND background_permit_id IS NOT NULL",
                    { row, _ -> readAuditedRun(row) },
                    permitId,
                ).singleOrNull()
        }

    override fun bind(
        permitId: UUID,
        remote: ExecutionDescriptor,
    ) = transaction.requireCommit {
        check(
            jdbc.update(
                "UPDATE snippet_execution_audit SET remote = CAST(? AS jsonb), failure = NULL " +
                    "WHERE id = ? AND background_permit_id IS NOT NULL AND result IS NULL",
                writeAuditJson(remote),
                permitId,
            ) == 1,
        )
    }

    override fun complete(
        permitId: UUID,
        result: CompletedRun,
    ) = transaction.requireCommit {
        jdbc.update(
            "UPDATE snippet_execution_audit SET result = CAST(? AS jsonb), failure = NULL, " +
                "finished_at = clock_timestamp() WHERE id = ? AND background_permit_id IS NOT NULL AND result IS NULL",
            writeAuditJson(result),
            permitId,
        )
        Unit
    }

    override fun failed(
        permitId: UUID,
        reason: String,
        terminal: Boolean,
    ) = transaction.requireCommit {
        jdbc.update(
            "UPDATE snippet_execution_audit SET failure = ?, finished_at = CASE WHEN ? " +
                "THEN clock_timestamp() ELSE NULL END WHERE id = ? " +
                "AND background_permit_id IS NOT NULL AND result IS NULL",
            reason.take(MAX_AUTOMATIC_REASON_LENGTH),
            terminal,
            permitId,
        )
        Unit
    }

    override fun results(jobId: UUID): List<AuditedRun> =
        transaction.read {
            jdbc.query(
                "SELECT DISTINCT ON (p.item_id) a.* FROM snippet_execution_audit a JOIN execution_permits p " +
                    "ON p.id = a.background_permit_id WHERE p.job_id = ? " +
                    "ORDER BY p.item_id, a.started_at DESC, a.id DESC",
                { row, _ -> readAuditedRun(row) },
                jobId,
            )
        }
}
