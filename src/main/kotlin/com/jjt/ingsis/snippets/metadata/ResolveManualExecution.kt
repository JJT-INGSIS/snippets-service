package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.execution.AuditedRun
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

private const val MAX_MANUAL_PROOF_LENGTH = 500

@Component
class ResolveManualExecution(
    private val audit: JdbcExecutionAudit,
    private val transaction: MetadataTransaction,
    private val capacity: JdbcExecutionCapacity,
    private val jdbc: JdbcTemplate,
) {
    // Operator proof covers remote termination AND fencing an original sender that could submit a delayed start.
    fun resolve(
        id: UUID,
        proof: (AuditedRun) -> String?,
    ) {
        val run = checkNotNull(audit.find(id))
        val evidence = checkNotNull(proof(run)) { "Remote termination not proven" }
        require(evidence.isNotBlank() && evidence.length <= MAX_MANUAL_PROOF_LENGTH)
        transaction.commit {
            capacity.lock()
            check(audit.find(id)?.remote == run.remote)
            audit.stopped(id, "REMOTE_TERMINATION_VERIFIED")
            jdbc.update("UPDATE execution_permits SET resolution_evidence = ? WHERE manual_run_id = ?", evidence, id)
        }
    }
}
