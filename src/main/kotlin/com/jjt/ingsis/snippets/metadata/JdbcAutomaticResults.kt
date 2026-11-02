package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.automatic.AutomaticItemProgress
import com.jjt.ingsis.snippets.automatic.AutomaticResults
import com.jjt.ingsis.snippets.automatic.CurrentAutomaticSnapshot
import com.jjt.ingsis.snippets.background.ItemState
import com.jjt.ingsis.snippets.creation.canonicalUuid
import com.jjt.ingsis.snippets.execution.SnapshotFingerprint
import com.jjt.ingsis.snippets.testcase.TestCaseStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcAutomaticResults(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
    private val snapshots: JdbcUpdateSnapshots,
    private val runs: JdbcAutomaticRuns,
    private val jobs: JdbcJobProgress,
    private val tests: TestCaseStore,
    private val fingerprints: SnapshotFingerprint,
) : AutomaticResults {
    override fun current(snippetId: UUID): CurrentAutomaticSnapshot? =
        transaction
            .join {
                val active =
                    jdbc
                        .query(
                            "SELECT content_reference FROM snippets WHERE id = ? AND state = 'CONFIRMED' FOR SHARE",
                            { row, _ -> row.getString(1) },
                            snippetId,
                        ).singleOrNull() ?: return@join emptyList()
                val id =
                    jdbc
                        .query(
                            "SELECT job_id FROM snippet_update_snapshots WHERE snippet_id = ? " +
                                "ORDER BY created_at DESC, job_id DESC LIMIT 1",
                            { row, _ -> row.getObject(1, UUID::class.java) },
                            snippetId,
                        ).singleOrNull() ?: return@join emptyList()
                val snapshot = snapshots.find(id) ?: return@join emptyList()
                val progress = jobs.progress(id) ?: return@join emptyList()
                val actualTests = tests.list(snippetId).associate { it.id to fingerprints.test(it.definition) }
                listOf(
                    CurrentAutomaticSnapshot(
                        update = snapshot,
                        progress = progress,
                        runs = runs.results(id),
                        items = items(id),
                        current =
                            active == snapshot.reference.value && actualTests ==
                                snapshot.tests.associate {
                                    it.id to it.hash
                                },
                    ),
                )
            }.singleOrNull()

    private fun items(jobId: UUID): List<AutomaticItemProgress> =
        jdbc.query(
            "SELECT item_key, state, last_error, result_reference FROM background_job_items WHERE job_id = ?",
            { row, _ ->
                val id =
                    canonicalUuid(row.getString("item_key"))
                        ?: throw MetadataPersistenceException(IllegalArgumentException("Invalid automatic test item"))
                AutomaticItemProgress(
                    testId = id,
                    state = ItemState.valueOf(row.getString("state")),
                    reason =
                        row.getString("last_error")
                            ?: row.getString("result_reference")?.takeUnless { canonicalUuid(it) != null },
                )
            },
            jobId,
        )
}
