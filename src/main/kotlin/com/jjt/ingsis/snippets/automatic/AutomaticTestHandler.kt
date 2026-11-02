package com.jjt.ingsis.snippets.automatic

import com.jjt.ingsis.snippets.background.BackgroundHandler
import com.jjt.ingsis.snippets.background.EventType
import com.jjt.ingsis.snippets.background.ItemState
import com.jjt.ingsis.snippets.background.JobStep
import com.jjt.ingsis.snippets.background.JobWork
import com.jjt.ingsis.snippets.background.NewJobItem
import com.jjt.ingsis.snippets.background.RemotePermit
import com.jjt.ingsis.snippets.background.WorkItem
import com.jjt.ingsis.snippets.background.WorkerSettings
import com.jjt.ingsis.snippets.execution.ExecutionSnapshot
import com.jjt.ingsis.snippets.execution.SnapshotFingerprint
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AutomaticTestHandler(
    private val snapshots: UpdateSnapshots,
    private val storage: SnippetContentStorage,
    private val fingerprints: SnapshotFingerprint,
    private val language: Language,
    private val runs: AutomaticRuns,
    private val remote: AutomaticRemoteRuns,
    private val settings: WorkerSettings,
) : BackgroundHandler {
    override val eventType: EventType = EventType.SnippetUpdated

    override fun step(work: JobWork): JobStep {
        val snapshot = snapshots.find(work.lease.jobId) ?: return JobStep.Failed("UPDATE_SNAPSHOT_MISSING")
        if (snapshot.released) return JobStep.Failed("UPDATE_SNAPSHOT_RELEASED")
        if (!work.job.selectionComplete) return select(work, snapshot)
        val item = work.items().firstOrNull() ?: return JobStep.Complete
        val test =
            snapshot.tests.find { it.id.toString() == item.key } ?: return failed(work, item, "TEST_SNAPSHOT_MISSING")
        val content = storage.read(snapshot.reference)
        if (content !is ReadResult.Found) {
            work.retry(item, "SNAPSHOT_STORAGE_UNAVAILABLE")
            return JobStep.Yield
        }
        if (fingerprints.code(snapshot.language, snapshot.version, content.code) != snapshot.codeHash) {
            return failed(work, item, "SNAPSHOT_CONTENT_MISMATCH")
        }
        val executor = language.executorFor(snapshot.language)
        if (executor !is ExecutionResult.Success) return failed(work, item, "UNSUPPORTED_EXECUTION")
        val execution =
            ExecutionSnapshot(
                snippetId = snapshot.snippetId,
                language = snapshot.language,
                version = snapshot.version,
                code = content.code,
                contentReference = snapshot.reference.value,
                codeHash = snapshot.codeHash,
                test = test,
            )
        val permit = work.beginRemote(item)
        if (permit == null) {
            work.defer(item, "WAITING_FOR_CAPACITY")
            return JobStep.Yield
        }
        val attempt = work.persist { runs.begin(permit, snapshot.actorId, execution) }
        return remote.start(work = work, permit = permit, attempt = attempt, executor = executor.value)
    }

    override fun reconcile(
        work: JobWork,
        permit: RemotePermit?,
    ): JobStep {
        if (permit == null) return JobStep.Unknown("REMOTE_OBLIGATION_MISSING")
        val attempt = runs.find(permit.id) ?: return JobStep.Unknown("START_ASSOCIATION_MISSING")
        val executor = language.executorFor(attempt.snapshot.language)
        if (executor !is ExecutionResult.Success) return JobStep.Unknown("EXECUTOR_UNAVAILABLE")
        return remote.recover(work = work, permit = permit, attempt = attempt, executor = executor.value)
    }

    private fun select(
        work: JobWork,
        snapshot: UpdateSnapshot,
    ): JobStep {
        val offset =
            work.job.cursor?.let {
                it.toIntOrNull() ?: return JobStep.Failed("INVALID_SELECTION_CURSOR")
            } ?: 0
        if (offset !in 0..snapshot.tests.size) return JobStep.Failed("INVALID_SELECTION_CURSOR")
        val page = snapshot.tests.drop(offset).take(settings.itemBatchSize)
        val items =
            page.map { test ->
                NewJobItem(
                    id =
                        UUID.nameUUIDFromBytes(
                            (snapshot.jobId.toString() + ":" + test.id).toByteArray(Charsets.UTF_8),
                        ),
                    jobId = snapshot.jobId,
                    key = test.id.toString(),
                    revisions = mapOf("code" to snapshot.codeHash, "test" to test.hash),
                    snapshotReference = snapshot.jobId.toString(),
                )
            }
        work.select(
            items = items,
            cursor = (offset + page.size).toString(),
            complete =
                offset + page.size == snapshot.tests.size,
        )
        return JobStep.Yield
    }

    private fun failed(
        work: JobWork,
        item: WorkItem,
        reason: String,
    ): JobStep {
        work.complete(item = item, reference = reason, state = ItemState.FAILED)
        return JobStep.Yield
    }
}
