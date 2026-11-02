package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.background.CapacitySettings
import com.jjt.ingsis.snippets.background.ExecutionCapacity
import com.jjt.ingsis.snippets.background.ItemState
import com.jjt.ingsis.snippets.background.JobLease
import com.jjt.ingsis.snippets.background.RemoteExecution
import com.jjt.ingsis.snippets.background.RemotePermit
import com.jjt.ingsis.snippets.background.RemoteTerminationEvidence
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.util.UUID

private const val MAX_REMOTE_PROOF_LENGTH = 500

// Durable permits are a different lifecycle from the expiring job lease.
@Suppress("TooManyFunctions")
@Repository
class JdbcExecutionCapacity(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
    private val jobs: JdbcJobProgress,
    private val settings: CapacitySettings,
) : ExecutionCapacity {
    private val json = JsonMapper.builder().findAndAddModules().build()

    fun enabled(): Boolean =
        settings.enabled || jdbc.queryForObject(
            "SELECT enabled FROM execution_capacity WHERE id = 1",
            Boolean::class.java,
        ) == true

    fun lock() {
        val control = jdbc.queryForMap("SELECT * FROM execution_capacity WHERE id = 1 FOR UPDATE")
        if (settings.enabled &&
            control["enabled"] != true
        ) {
            jdbc.update("UPDATE execution_capacity SET enabled = TRUE WHERE id = 1")
        }
        if (!settings.enabled && control["enabled"] != true) return
        check(control["active_limit"] == settings.activeLimit && control["manual_reserve"] == settings.manualReserve) {
            "Capacity configuration differs from durable budget; drain and reconfigure before enabling"
        }
        jdbc.update(
            "INSERT INTO execution_permits (id, kind, manual_run_id, state, created_at) " +
                "SELECT a.id, 'MANUAL', a.id, 'UNKNOWN', a.started_at FROM snippet_execution_audit a " +
                "WHERE a.reserved AND NOT EXISTS (SELECT 1 FROM execution_permits p WHERE " +
                "p.manual_run_id = a.id) " +
                "ON CONFLICT DO NOTHING",
        )
    }

    fun canReserveManual(): Boolean = !enabled() || room(background = false)

    private fun room(background: Boolean): Boolean {
        val held =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM execution_permits WHERE state <> 'RELEASED'",
                    Int::class.java,
                ),
            )
        val maximum = settings.activeLimit - if (background) settings.manualReserve else 0
        return held < maximum
    }

    fun reserveManual(id: UUID): Boolean {
        // Caller holds the capacity row from the admission check through this insert.
        if (!enabled()) return true
        if (jdbc.queryForObject("SELECT count(*) FROM execution_permits WHERE id = ?", Int::class.java, id) !=
            0
        ) {
            return true
        }
        jdbc.update(
            "INSERT INTO execution_permits (id, kind, manual_run_id, state) VALUES (?, 'MANUAL', ?, " +
                "'RESERVED')",
            id,
            id,
        )
        return true
    }

    fun releaseManual(id: UUID) {
        // V7 can adopt an existing audit before admission is enabled; its terminal state still releases it.
        jdbc.update(
            "UPDATE execution_permits SET state = 'RELEASED', updated_at = clock_timestamp() " +
                "WHERE manual_run_id = ?",
            id,
        )
    }

    fun uncertainManual(id: UUID) {
        jdbc.update(
            "UPDATE execution_permits SET state = 'UNKNOWN', updated_at = clock_timestamp() " +
                "WHERE manual_run_id = ? AND state <> 'RELEASED'",
            id,
        )
    }

    fun validateEffective(limit: Long?) {
        if (enabled() && limit != settings.activeLimit.toLong()) {
            throw MetadataPersistenceException(IllegalStateException("EXECUTION_CAPACITY_MISMATCH"))
        }
    }

    override fun held(jobId: UUID): RemotePermit? =
        transaction.read {
            jdbc
                .query(
                    "SELECT * FROM execution_permits WHERE job_id = ? AND state <> 'RELEASED'",
                    { row, _ -> read(row) },
                    jobId,
                ).singleOrNull()
        }

    override fun begin(
        lease: JobLease,
        itemId: UUID,
    ): RemotePermit? =
        jobs
            .owned(lease) {
                lock()
                check(enabled()) { "Background capacity must be explicitly enabled" }
                val occupied =
                    jdbc.queryForObject(
                        "SELECT count(*) FROM execution_permits WHERE kind = 'BACKGROUND' AND state " +
                            "<> 'RELEASED'",
                        Int::class.java,
                    ) != 0
                if (occupied || !room(background = true)) {
                    emptyList()
                } else {
                    val item =
                        jdbc.queryForList(
                            "SELECT id FROM background_job_items WHERE id = ? AND job_id = ? AND " +
                                "state = 'RUNNING'",
                            itemId,
                            lease.jobId,
                        )
                    check(item.isNotEmpty())
                    val id = UUID.randomUUID()
                    jdbc.update(
                        "INSERT INTO execution_permits (id, kind, job_id, item_id, state) " +
                            "VALUES (?, 'BACKGROUND', ?, ?, 'RESERVED')",
                        id,
                        lease.jobId,
                        itemId,
                    )
                    listOf(
                        RemotePermit(id = id, jobId = lease.jobId, itemId = itemId, state = "RESERVED", remote = null),
                    )
                }
            }.singleOrNull()

    override fun bind(
        lease: JobLease,
        permitId: UUID,
        remote: RemoteExecution,
    ) = jobs.owned(lease) {
        require(remote.id.isNotBlank())
        validateEffective(remote.activeLimit)
        check(
            jdbc.update(
                "UPDATE execution_permits SET remote = CAST(? AS jsonb), updated_at = clock_timestamp() " +
                    "WHERE id = ? AND job_id = ? AND state <> 'RELEASED' AND remote IS NULL",
                json.writeValueAsString(remote),
                permitId,
                lease.jobId,
            ) == 1,
        )
    }

    override fun complete(
        lease: JobLease,
        permitId: UUID,
        result: String?,
        state: ItemState,
    ) = jobs.owned(lease) {
        val permit = requirePermit(lease, permitId)
        lock()
        jobs.completeItem(lease = lease, itemId = permit.itemId, result = result, state = state)
        release(permitId, "REMOTE_RESULT_PERSISTED")
    }

    override fun rejected(
        lease: JobLease,
        permitId: UUID,
        reason: String,
    ) = jobs.owned(lease) {
        val permit = requirePermit(lease, permitId)
        check(permit.remote == null) { "A known remote session is not a rejected start" }
        lock()
        jobs.retryItem(lease, permit.itemId, reason)
        release(permitId, "REMOTE_START_REJECTED")
    }

    override fun resolve(
        lease: JobLease,
        permitId: UUID,
        evidence: RemoteTerminationEvidence,
    ) {
        // Verification may use remote I/O: never execute it while holding a database transaction.
        val permit = checkNotNull(held(lease.jobId)).also { check(it.id == permitId) }
        val proof =
            checkNotNull(evidence.confirm(permit)) { "Termination is not proven; keep UNKNOWN and its capacity" }
        require(proof.isNotBlank() && proof.length <= MAX_REMOTE_PROOF_LENGTH)
        jobs.owned(lease) {
            check(requirePermit(lease, permitId).remote == permit.remote) {
                "Remote association changed during verification"
            }
            lock()
            jobs.completeItem(lease = lease, itemId = permit.itemId, result = null, state = ItemState.FAILED)
            release(permitId, proof)
        }
    }

    private fun requirePermit(
        lease: JobLease,
        id: UUID,
    ): RemotePermit = checkNotNull(held(lease.jobId)).also { check(it.id == id) }

    private fun release(
        id: UUID,
        evidence: String,
    ) {
        jdbc.update(
            "UPDATE execution_permits SET state = 'RELEASED', resolution_evidence = ?, updated_at = " +
                "clock_timestamp() " +
                "WHERE id = ? AND state <> 'RELEASED'",
            evidence.take(MAX_REMOTE_PROOF_LENGTH),
            id,
        )
    }

    private fun read(row: ResultSet): RemotePermit =
        RemotePermit(
            id = row.getObject("id", UUID::class.java),
            jobId = row.getObject("job_id", UUID::class.java),
            itemId = row.getObject("item_id", UUID::class.java),
            state = row.getString("state"),
            remote = row.getString("remote")?.let { json.readValue(it, RemoteExecution::class.java) },
        )
}
