package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.background.JobLease
import com.jjt.ingsis.snippets.language.printscript.PrintScriptProperties
import com.jjt.ingsis.snippets.revalidation.ParserValidationAdmission
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcParserValidationAdmission(
    private val jdbc: org.springframework.jdbc.core.JdbcTemplate,
    private val jobs: JdbcJobProgress,
    private val capacity: JdbcExecutionCapacity,
    private val transaction: MetadataTransaction,
    properties: PrintScriptProperties,
) : ParserValidationAdmission {
    // Admission horizon only: expiration never claims that a remote analysis stopped.
    private val horizon =
        properties.connectTimeout
            .plus(properties.readTimeout)
            .multipliedBy(2)
            .plusSeconds(5)

    override fun acquire(
        lease: JobLease,
        itemId: UUID,
    ): UUID? =
        jobs
            .owned(lease) {
                capacity.lock()
                check(capacity.enabled()) { "Enable shared capacity before processing parser jobs" }
                if (!capacity.canReserveAnalysis()) return@owned emptyList<UUID>()
                val owned =
                    jdbc.queryForList(
                        "SELECT id FROM background_job_items WHERE id = ? AND job_id = ? AND state = 'RUNNING'",
                        itemId,
                        lease.jobId,
                    )
                check(owned.isNotEmpty())
                val token = UUID.randomUUID()
                jdbc.update(
                    "INSERT INTO parser_analysis_admission (id, token, job_id, item_id, expires_at) " +
                        "VALUES (1, ?, ?, ?, clock_timestamp() + (? * INTERVAL '1 millisecond')) " +
                        "ON CONFLICT (id) DO UPDATE SET token = EXCLUDED.token, job_id = EXCLUDED.job_id, " +
                        "item_id = EXCLUDED.item_id, expires_at = EXCLUDED.expires_at",
                    token,
                    lease.jobId,
                    itemId,
                    horizon.toMillis(),
                )
                listOf(token)
            }.singleOrNull()

    override fun release(token: UUID) {
        transaction.commit {
            capacity.lock()
            jdbc.update("DELETE FROM parser_analysis_admission WHERE id = 1 AND token = ?", token)
        }
    }
}
