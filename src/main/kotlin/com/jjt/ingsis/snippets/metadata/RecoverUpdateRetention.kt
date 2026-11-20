package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.storage.ContentReference
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class RecoverUpdateRetention(
    private val jdbc: JdbcTemplate,
    private val snapshots: JdbcUpdateSnapshots,
    private val cleanup: RetainedContentCleanup,
    private val transaction: MetadataTransaction,
) {
    private val logger = LoggerFactory.getLogger(RecoverUpdateRetention::class.java)

    @Scheduled(fixedDelayString = "\${execution.retention.delay-ms:30000}")
    fun recover() {
        try {
            val completed =
                transaction.read {
                    jdbc.query(
                        "SELECT s.job_id FROM snippet_update_snapshots s JOIN background_jobs j ON j.id = s.job_id " +
                            "WHERE s.released_at IS NULL AND j.finished_at IS NOT NULL ORDER BY s.created_at LIMIT 100",
                        { row, _ -> row.getObject(1, UUID::class.java) },
                    )
                }
            completed.forEach { snapshots.release(it) }
            val unused =
                transaction.read {
                    jdbc.query(
                        "SELECT c.reference FROM retained_content c WHERE c.state <> 'DELETED' AND " +
                            "NOT EXISTS (SELECT 1 FROM snippets n WHERE n.content_reference = c.reference) AND " +
                            "NOT EXISTS (SELECT 1 FROM snippet_update_snapshots s WHERE " +
                            "s.content_reference = c.reference AND s.released_at IS NULL) " +
                            "AND NOT EXISTS (SELECT 1 FROM parser_validation_snapshots p " +
                            "JOIN background_jobs j ON j.id = p.job_id " +
                            "WHERE p.source_reference = c.reference AND j.finished_at IS NULL) " +
                            "ORDER BY c.reference LIMIT 100",
                        { row, _ -> ContentReference(row.getString(1)) },
                    )
                }
            unused.forEach { cleanup.discard(it) }
        } catch (_: MetadataPersistenceException) {
            logger.warn("Update snapshot retention recovery could not persist its progress")
        }
    }
}
