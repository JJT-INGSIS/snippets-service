package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.execution.AuditReservation
import com.jjt.ingsis.snippets.execution.AuditedRun
import com.jjt.ingsis.snippets.execution.CompletedRun
import com.jjt.ingsis.snippets.execution.ExecutionAudit
import com.jjt.ingsis.snippets.language.execution.ExecutionDescriptor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcExecutionAudit(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
    private val capacity: JdbcExecutionCapacity,
) : ExecutionAudit {
    override fun reserve(run: AuditedRun): Boolean = reserveForStart(run) == AuditReservation.RESERVED

    override fun reserveForStart(run: AuditedRun): AuditReservation =
        transaction.commit {
            capacity.lock()
            if (jdbc.queryForObject(
                    "SELECT count(*) FROM snippet_execution_audit WHERE actor_id = ? AND reserved",
                    Int::class.java,
                    run.actorId,
                ) != 0
            ) {
                return@commit AuditReservation.ACTOR_BUSY
            }
            if (!capacity.canReserveManual()) return@commit AuditReservation.CAPACITY
            val inserted =
                jdbc.update(
                    "INSERT INTO snippet_execution_audit (id, actor_id, snippet_id, test_id, mode, " +
                        "snapshot, started_at) " +
                        "VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?) ON CONFLICT DO NOTHING",
                    run.id,
                    run.actorId,
                    run.snapshot.snippetId,
                    run.snapshot.test?.id,
                    run.snapshot.mode().name,
                    writeAuditJson(run.snapshot),
                    Timestamp.from(run.startedAt),
                ) == 1
            if (!inserted) {
                AuditReservation.ACTOR_BUSY
            } else {
                check(capacity.reserveManual(run.id))
                AuditReservation.RESERVED
            }
        }

    override fun bind(
        id: UUID,
        descriptor: ExecutionDescriptor,
    ) = transaction.commit {
        capacity.lock()
        capacity.validateEffective(descriptor.limits["activeGlobal"])
        check(
            jdbc.update(
                "UPDATE snippet_execution_audit SET remote = CAST(? AS jsonb) WHERE id = ? AND reserved",
                writeAuditJson(descriptor),
                id,
            ) == 1,
        )
    }

    override fun complete(
        id: UUID,
        result: CompletedRun,
    ) = transaction.commit {
        capacity.lock()
        jdbc.update(
            "UPDATE snippet_execution_audit SET result = CAST(? AS jsonb), finished_at = ?, " +
                "failure = NULL, reserved = FALSE WHERE id = ? AND result IS NULL",
            writeAuditJson(result),
            Timestamp.from(Instant.now()),
            id,
        )
        capacity.releaseManual(id)
        Unit
    }

    override fun unknown(
        id: UUID,
        reason: String,
        release: Boolean,
    ) = transaction.commit {
        capacity.lock()
        val releaseCapacity = release && (!capacity.enabled() || find(id)?.remote == null)
        jdbc.update(
            "UPDATE snippet_execution_audit SET failure = ?, reserved = ?, finished_at = ? " +
                "WHERE id = ? AND result IS NULL AND reserved",
            reason,
            !releaseCapacity,
            if (releaseCapacity) Timestamp.from(Instant.now()) else null,
            id,
        )
        if (releaseCapacity) capacity.releaseManual(id) else capacity.uncertainManual(id)
        Unit
    }

    override fun stopped(
        id: UUID,
        reason: String,
    ) = transaction.join {
        capacity.lock()
        jdbc.update(
            "UPDATE snippet_execution_audit SET failure = ?, reserved = FALSE, finished_at = " +
                "clock_timestamp() " +
                "WHERE id = ? AND result IS NULL AND reserved",
            reason,
            id,
        )
        capacity.releaseManual(id)
        Unit
    }

    override fun reject(
        id: UUID,
        reason: String,
    ) = stopped(id, reason)

    override fun find(id: UUID): AuditedRun? =
        transaction.read {
            jdbc
                .query(
                    "SELECT * FROM snippet_execution_audit WHERE id = ? AND background_permit_id IS NULL",
                    { row, _ -> readAuditedRun(row) },
                    id,
                ).singleOrNull()
        }

    override fun reserved(): List<AuditedRun> =
        transaction.read {
            jdbc.query(
                "SELECT * FROM snippet_execution_audit WHERE reserved AND background_permit_id IS NULL " +
                    "ORDER BY started_at",
                { row, _ -> readAuditedRun(row) },
            )
        }
}
