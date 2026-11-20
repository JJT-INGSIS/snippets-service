package com.jjt.ingsis.snippets.revalidation

import com.jjt.ingsis.snippets.background.BackgroundHandler
import com.jjt.ingsis.snippets.background.EventType
import com.jjt.ingsis.snippets.background.ItemState
import com.jjt.ingsis.snippets.background.JobStep
import com.jjt.ingsis.snippets.background.JobWork
import com.jjt.ingsis.snippets.background.NewJobItem
import com.jjt.ingsis.snippets.background.WorkItem
import com.jjt.ingsis.snippets.background.WorkerSettings
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.ProcessorCheck
import com.jjt.ingsis.snippets.language.ValidationResult
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ParserVersionActivatedHandler(
    private val activations: ParserActivations,
    private val validations: ParserValidations,
    private val admission: ParserValidationAdmission,
    private val language: Language,
    private val storage: SnippetContentStorage,
    private val settings: WorkerSettings,
) : BackgroundHandler {
    override val eventType = EventType.ParserVersionActivated

    override fun step(work: JobWork): JobStep {
        val active = target(work) ?: return JobStep.Superseded
        if (!work.job.selectionComplete) select(work, active)
        val item = work.items().firstOrNull() ?: return JobStep.Complete
        val step = process(work, item, active)
        return if (activations.active(active.scope)?.id != active.id) JobStep.Superseded else step
    }

    private fun target(work: JobWork): ParserActivation? {
        val event = work.job.event
        val language = event.scope["language"] ?: return null
        val version = event.scope["version"] ?: return null
        return activations.active(ParserScope(language, version))?.takeIf {
            it.id.toString() == event.revisions["activation"] &&
                it.processor.value == event.revisions["processor"] && it.jobId == event.jobId
        }
    }

    private fun process(
        work: JobWork,
        item: WorkItem,
        active: ParserActivation,
    ): JobStep {
        val snapshot = validations.snapshot(work.lease.jobId, UUID.fromString(item.key))
        if (snapshot == null || !validations.current(snapshot, active.id)) {
            work.complete(item, null, ItemState.SUPERSEDED)
            return JobStep.Complete
        }
        val token = admission.acquire(work.lease, item.id)
        if (token == null) {
            work.persist { workStoreDefer(work, item) }
            return JobStep.Yield
        }
        try {
            analyze(work = work, item = item, snapshot = snapshot, active = active)
        } finally {
            admission.release(token)
        }
        return JobStep.Complete
    }

    private fun analyze(
        work: JobWork,
        item: WorkItem,
        snapshot: ParserValidationSnapshot,
        active: ParserActivation,
    ) {
        val read = storage.read(snapshot.reference)
        if (read !is ReadResult.Found) {
            work.retry(item, "SNAPSHOT_UNAVAILABLE")
            return
        }
        val response = language.validateAttributed(snapshot.scope.language, snapshot.scope.version, read.code)
        when {
            response.result is ValidationResult.Failed -> {
                work.retry(item, "VALIDATION_UNAVAILABLE")
            }

            response.processor.checkAgainst(active.processor) != ProcessorCheck.ACCREDITED -> {
                work.retry(item, "VALIDATION_PROCESSOR_MISMATCH")
            }

            else -> {
                persist(work = work, item = item, snapshot = snapshot, active = active, result = response.result)
            }
        }
    }

    private fun select(
        work: JobWork,
        active: ParserActivation,
    ) = work.persist {
        val page =
            validations.select(
                jobId = work.lease.jobId,
                scope = active.scope,
                after = work.job.cursor?.let(UUID::fromString),
                limit = settings.itemBatchSize,
            )
        val items =
            page.map { snapshot ->
                NewJobItem(
                    id = UUID.randomUUID(),
                    jobId = work.lease.jobId,
                    key = snapshot.snippetId.toString(),
                    revisions =
                        mapOf(
                            "code" to snapshot.reference.value,
                            "activation" to active.id.toString(),
                            "processor" to active.processor.value,
                        ),
                    snapshotReference = snapshot.reference.value,
                )
            }
        work.select(
            items,
            page.lastOrNull()?.snippetId?.toString() ?: work.job.cursor,
            page.size < settings.itemBatchSize,
        )
    }

    private fun persist(
        work: JobWork,
        item: WorkItem,
        snapshot: ParserValidationSnapshot,
        active: ParserActivation,
        result: ValidationResult,
    ) {
        if (result != ValidationResult.Valid && result !is ValidationResult.Invalid) {
            work.complete(item = item, reference = null, state = ItemState.FAILED, reason = "UNSUPPORTED_VALIDATION")
            return
        }
        work.persist {
            val published =
                validations.publish(
                    snapshot = snapshot,
                    activation = active,
                    resultId = item.id,
                    valid = result == ValidationResult.Valid,
                    diagnostics = (result as? ValidationResult.Invalid)?.diagnostics.orEmpty(),
                )
            work.complete(
                item = item,
                reference = if (published) item.id.toString() else null,
                state = if (published) ItemState.COMPLETED else ItemState.SUPERSEDED,
            )
        }
    }

    private fun workStoreDefer(
        work: JobWork,
        item: WorkItem,
    ) {
        work.defer(item, "ANALYSIS_CAPACITY_UNAVAILABLE")
    }
}
