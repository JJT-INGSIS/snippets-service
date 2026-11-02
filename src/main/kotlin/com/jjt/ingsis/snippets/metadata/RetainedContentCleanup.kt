package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.automatic.ObsoleteContent
import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.DeleteResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class RetainedContentCleanup(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
    private val storage: SnippetContentStorage,
) : ObsoleteContent {
    private val logger = LoggerFactory.getLogger(RetainedContentCleanup::class.java)

    override fun discard(reference: ContentReference) {
        try {
            remove(reference)
        } catch (_: MetadataPersistenceException) {
            logger.warn("Obsolete content cleanup deferred after persistence failure")
        }
    }

    private fun remove(reference: ContentReference) {
        val token = claim(reference) ?: return
        val deleted = storage.delete(reference) == DeleteResult.Deleted
        transaction.commit {
            jdbc.update(
                "UPDATE retained_content SET state = ?, cleanup_token = NULL, cleanup_until = NULL, " +
                    "last_error = ? WHERE reference = ? AND cleanup_token = ?",
                if (deleted) "DELETED" else "DELETING",
                if (deleted) null else "STORAGE_UNAVAILABLE",
                reference.value,
                token,
            )
            Unit
        }
    }

    private fun claim(reference: ContentReference): UUID? =
        transaction
            .commit {
                jdbc.update(
                    "INSERT INTO retained_content (reference) VALUES (?) ON CONFLICT DO NOTHING",
                    reference.value,
                )
                val available =
                    jdbc.queryForList(
                        "SELECT reference FROM retained_content WHERE reference = ? AND state <> 'DELETED' " +
                            "AND (cleanup_until IS NULL OR cleanup_until <= clock_timestamp()) FOR UPDATE",
                        reference.value,
                    )
                if (available.isEmpty() || held(reference)) {
                    emptyList()
                } else {
                    val token = UUID.randomUUID()
                    jdbc.update(
                        "UPDATE retained_content SET state = 'DELETING', cleanup_token = ?, " +
                            "cleanup_until = clock_timestamp() + INTERVAL '30 seconds' WHERE reference = ?",
                        token,
                        reference.value,
                    )
                    listOf(token)
                }
            }.singleOrNull()

    private fun held(reference: ContentReference): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM snippets WHERE content_reference = ?) OR EXISTS " +
                "(SELECT 1 FROM snippet_update_snapshots " +
                "WHERE content_reference = ? AND released_at IS NULL) OR EXISTS " +
                "(SELECT 1 FROM parser_validation_snapshots s JOIN background_jobs j ON j.id = s.job_id " +
                "WHERE s.source_reference = ? AND j.finished_at IS NULL)",
            Boolean::class.java,
            reference.value,
            reference.value,
            reference.value,
        ) == true
}
