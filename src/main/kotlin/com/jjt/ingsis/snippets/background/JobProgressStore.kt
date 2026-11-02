package com.jjt.ingsis.snippets.background

import java.util.UUID

data class JobProgress(
    val id: UUID,
    val status: JobState,
    val processed: Long,
    val failed: Long,
    val pending: Long,
    val selectionComplete: Boolean,
)

// One fenced persistence boundary; splitting it would scatter the ownership checks.
@Suppress("TooManyFunctions")
interface JobProgressStore {
    fun <T : Any> persist(
        lease: JobLease,
        operation: () -> T,
    ): T

    fun find(id: UUID): StoredJob?

    fun claim(id: UUID): StoredJob?

    fun renew(lease: JobLease): Boolean

    fun defer(id: UUID)

    fun finish(
        lease: JobLease,
        step: JobStep,
    ): Boolean

    fun select(
        lease: JobLease,
        items: List<NewJobItem>,
        cursor: String?,
        complete: Boolean,
    )

    fun items(lease: JobLease): List<WorkItem>

    fun completeItem(
        lease: JobLease,
        itemId: UUID,
        result: String?,
        state: ItemState,
    )

    fun retryItem(
        lease: JobLease,
        itemId: UUID,
        reason: String,
    )

    fun progress(id: UUID): JobProgress?

    fun reconciliations(): List<UUID>
}
