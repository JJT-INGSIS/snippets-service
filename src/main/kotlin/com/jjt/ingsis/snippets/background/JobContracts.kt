package com.jjt.ingsis.snippets.background

import java.time.Instant
import java.util.UUID

enum class JobState { PENDING, RUNNING, RETRY, UNKNOWN, COMPLETED, PARTIAL, FAILED, SUPERSEDED }

enum class ItemState { PENDING, RUNNING, RETRY, UNKNOWN, COMPLETED, FAILED, SUPERSEDED }

data class JobLease(
    val jobId: UUID,
    val token: UUID,
)

data class StoredJob(
    val event: DomainEvent,
    val state: JobState,
    val selectionComplete: Boolean,
    val attempts: Int,
    val nextAttempt: Instant,
    val lease: JobLease?,
    val cursor: String?,
)

data class WorkItem(
    val id: UUID,
    val key: String,
    val state: ItemState,
    val attempts: Int,
    val revisions: Map<String, String>,
    val snapshotReference: String?,
    val resultReference: String?,
)

sealed interface JobStep {
    data object Complete : JobStep

    data object Yield : JobStep

    data object Superseded : JobStep

    data class Retry(
        val reason: String,
    ) : JobStep

    data class Failed(
        val reason: String,
    ) : JobStep

    data class Unknown(
        val reason: String,
    ) : JobStep
}

data class RemoteExecution(
    val id: String,
    val processor: String?,
    val activeLimit: Long? = null,
    val context: Map<String, String> = emptyMap(),
)

data class RemotePermit(
    val id: UUID,
    val jobId: UUID,
    val itemId: UUID,
    val state: String,
    val remote: RemoteExecution?,
)

interface BackgroundHandler {
    val eventType: EventType

    fun step(work: JobWork): JobStep

    fun reconcile(
        work: JobWork,
        permit: RemotePermit?,
    ): JobStep = JobStep.Unknown("RECONCILIATION_REQUIRED")
}

class LostJobLease : RuntimeException("Job reservation is no longer owned")

fun interface RemoteTerminationEvidence {
    // Implementations must verify termination/absence independently, never infer it from lease age or timeout.
    // Also fence the original sender: it must not be able to submit a delayed start after this verification.
    fun confirm(permit: RemotePermit): String?
}
