package com.jjt.ingsis.snippets.automatic

import com.jjt.ingsis.snippets.creation.draft
import com.jjt.ingsis.snippets.metadata.JdbcJobRecords
import com.jjt.ingsis.snippets.metadata.JdbcSnippetUpdateCommit
import com.jjt.ingsis.snippets.metadata.JdbcUpdateSnapshots
import com.jjt.ingsis.snippets.metadata.JsonSnapshotFingerprint
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.metadata.MetadataTransaction
import com.jjt.ingsis.snippets.metadata.RetainedContentCleanup
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.metadata.creationRequest
import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.DeleteResult
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StorageUnavailable
import com.jjt.ingsis.snippets.storage.StoreResult
import com.jjt.ingsis.snippets.testcase.TestCaseDefinition
import com.jjt.ingsis.snippets.testcase.TestCaseStore
import com.jjt.ingsis.snippets.update.UpdateCandidate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["execution.retention.delay-ms=3600000"],
)
@Import(MetadataTestConfiguration::class)
class UpdateSnapshotsIntegrationTest
    @Autowired
    constructor(
        private val store: SnippetMetadataStore,
        private val links: SnippetContentLinks,
        private val storage: SnippetContentStorage,
        private val tests: TestCaseStore,
        private val commits: SnippetUpdateCommit,
        private val snapshots: UpdateSnapshots,
        private val jdbc: JdbcTemplate,
        private val transaction: MetadataTransaction,
    ) {
        private val fingerprints = JsonSnapshotFingerprint()

        @Test
        fun `two rapid updates retain their code and the exact test set independently`() {
            val id = snippet()
            val definition = TestCaseDefinition(listOf("", "á", "á"), listOf("", "same", "same"))
            tests.create(id, definition, 10)
            val first = replace(id, "println(1);")
            tests.create(id, TestCaseDefinition(emptyList(), emptyList()), 10)
            val second = replace(id, "println(2);")
            val saved = saved(id)
            assertThat(saved).hasSize(2)
            assertThat(saved.map { it.jobId }.distinct()).hasSize(2)
            assertThat(
                saved
                    .first()
                    .tests
                    .single()
                    .definition,
            ).isEqualTo(definition)
            assertThat(saved.last().tests).hasSize(2)
            assertThat(saved.first().reference).isEqualTo(first)
            assertThat(saved.last().reference).isEqualTo(second)
            assertThat(saved.first().codeHash).isEqualTo(fingerprints.code("printscript", "1.0", "println(1);"))
            val restored = JdbcUpdateSnapshots(jdbc, transaction).find(saved.first().jobId)
            assertThat(restored).isEqualTo(saved.first())
            cleanup().discard(first)
            assertThat(storage.read(first)).isEqualTo(ReadResult.Found("println(1);"))
            assertThat(storage.read(second)).isEqualTo(ReadResult.Found("println(2);"))
        }

        @Test
        fun `outbox failure rolls back the real update snapshot and job together`() {
            val id = snippet()
            val before = links.findLinked(id)
            val jobs =
                mock(JdbcJobRecords::class.java) {
                    throw MetadataPersistenceException(IllegalStateException("outbox failed"))
                }
            val failing =
                JdbcSnippetUpdateCommit(
                    transaction = transaction,
                    links = links,
                    tests = tests,
                    fingerprints = fingerprints,
                    jobs = jobs,
                    jdbc = jdbc,
                )
            val reference = stored("println(9);")
            assertThatThrownBy { failing.commit(candidate(id, "println(9);"), reference) }
                .isInstanceOf(MetadataPersistenceException::class.java)
            assertThat(links.findLinked(id)).isEqualTo(before)
            assertThat(saved(id)).isEmpty()
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM background_jobs WHERE scope ->> 'snippetId' = ?",
                    Int::class.java,
                    id.toString(),
                ),
            ).isZero()
            // An uncertain write is retained, never deleted speculatively.
            assertThat(storage.read(reference)).isEqualTo(ReadResult.Found("println(9);"))
        }

        @Test
        fun `shared references release idempotently without deleting active or other job content`() {
            val id = snippet()
            val reference = stored("println(1);")
            commits.commit(candidate(id, "println(1);"), reference)
            commits.commit(candidate(id, "println(1);"), reference)
            val saved = saved(id)
            terminal(saved.first().jobId, "COMPLETED")
            assertThat(snapshots.release(saved.first().jobId)).isTrue()
            assertThat(snapshots.release(saved.first().jobId)).isTrue()
            cleanup().discard(reference)
            assertThat(storage.read(reference)).isInstanceOf(ReadResult.Found::class.java)
            terminal(saved.last().jobId, "SUPERSEDED")
            assertThat(snapshots.release(saved.last().jobId)).isTrue()
            cleanup().discard(reference)
            assertThat(storage.read(reference)).isInstanceOf(ReadResult.Found::class.java)
            replace(id, "println(2);")
            cleanup().discard(reference)
            assertThat(storage.read(reference)).isEqualTo(ReadResult.Missing)
            assertThat(saved(id).take(2)).allMatch { it.released }
        }

        @Test
        fun `pending and unknown retain snapshots even across restart`() {
            val id = snippet()
            val reference = replace(id, "println(1);")
            val snapshot = saved(id).single()
            assertThat(snapshots.release(snapshot.jobId)).isFalse()
            jdbc.update("UPDATE background_jobs SET state = 'UNKNOWN' WHERE id = ?", snapshot.jobId)
            assertThat(JdbcUpdateSnapshots(jdbc, transaction).release(snapshot.jobId)).isFalse()
            replace(id, "println(2);")
            cleanup().discard(reference)
            assertThat(storage.read(reference)).isInstanceOf(ReadResult.Found::class.java)
        }

        @Test
        fun `storage cleanup failure remains recoverable and cannot resurrect a fenced reference`() {
            val id = snippet()
            val reference = replace(id, "println(1);")
            val snapshot = saved(id).single()
            replace(id, "println(2);")
            terminal(snapshot.jobId, "FAILED")
            assertThat(snapshots.release(snapshot.jobId)).isTrue()
            val fail = AtomicBoolean(true)
            val unreliable =
                object : SnippetContentStorage by storage {
                    override fun delete(reference: ContentReference): DeleteResult =
                        if (fail.getAndSet(false)) StorageUnavailable else storage.delete(reference)
                }
            val recovery = RetainedContentCleanup(jdbc, transaction, unreliable)
            recovery.discard(reference)
            assertThat(storage.read(reference)).isInstanceOf(ReadResult.Found::class.java)
            assertThatThrownBy { commits.commit(candidate(id, "println(1);"), reference) }
                .isInstanceOf(MetadataPersistenceException::class.java)
            recovery.discard(reference)
            assertThat(storage.read(reference)).isEqualTo(ReadResult.Missing)
        }

        @Test
        fun `a remote obligation prevents release even if a root was incorrectly marked terminal`() {
            val id = snippet()
            val reference = replace(id, "println(1);")
            val snapshot = saved(id).single()
            val itemId = UUID.randomUUID()
            transaction.commit {
                JdbcJobRecords(jdbc, transaction).insertItem(
                    com.jjt.ingsis.snippets.background.NewJobItem(
                        id = itemId,
                        jobId = snapshot.jobId,
                        key = "held",
                        revisions = mapOf("code" to snapshot.codeHash),
                    ),
                )
            }
            jdbc.update(
                "INSERT INTO execution_permits (id, kind, job_id, item_id, state) " +
                    "VALUES (?, 'BACKGROUND', ?, ?, 'UNKNOWN')",
                UUID.randomUUID(),
                snapshot.jobId,
                itemId,
            )
            terminal(snapshot.jobId, "FAILED")
            assertThat(snapshots.release(snapshot.jobId)).isFalse()
            replace(id, "println(2);")
            cleanup().discard(reference)
            assertThat(storage.read(reference)).isInstanceOf(ReadResult.Found::class.java)
            assertThat(snapshots.find(snapshot.jobId)?.released).isFalse()
        }

        @Test
        fun `empty test set is durable without creating executions`() {
            val id = snippet()
            replace(id, "println(1);")
            assertThat(saved(id).single().tests).isEmpty()
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM snippet_execution_audit WHERE snippet_id = ?",
                    Int::class.java,
                    id,
                ),
            ).isZero()
        }

        private fun snippet(): UUID {
            val request = creationRequest()
            store.prepareCreation(request)
            val found =
                store.findCreation(
                    request.key,
                    request.actorId,
                ) as com.jjt.ingsis.snippets.metadata.CreationLookup.Found
            val id = found.metadata.id
            links.linkContent(id, stored(draft().code))
            checkNotNull(store.confirmCreation(id))
            return id
        }

        private fun candidate(
            id: UUID,
            code: String,
        ): UpdateCandidate = UpdateCandidate(id, draft().copy(code = code), "dev-juan")

        private fun stored(code: String): ContentReference = (storage.store(code) as StoreResult.Stored).reference

        private fun replace(
            id: UUID,
            code: String,
        ): ContentReference =
            stored(code).also {
                commits.commit(candidate(id, code), it)
            }

        private fun saved(id: UUID): List<UpdateSnapshot> =
            jdbc.query(
                "SELECT job_id FROM snippet_update_snapshots WHERE snippet_id = ? ORDER BY created_at, job_id",
                { row, _ -> checkNotNull(snapshots.find(row.getObject(1, UUID::class.java))) },
                id,
            )

        private fun cleanup(): RetainedContentCleanup = RetainedContentCleanup(jdbc, transaction, storage)

        private fun terminal(
            jobId: UUID,
            state: String,
        ) {
            jdbc.update(
                "UPDATE background_jobs SET state = ?, finished_at = clock_timestamp() WHERE id = ?",
                state,
                jobId,
            )
        }
    }
