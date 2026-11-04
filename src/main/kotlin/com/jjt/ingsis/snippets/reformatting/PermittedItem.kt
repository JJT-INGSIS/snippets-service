package com.jjt.ingsis.snippets.reformatting

import com.jjt.ingsis.snippets.background.ItemState
import com.jjt.ingsis.snippets.background.JobWork
import com.jjt.ingsis.snippets.background.RemotePermit
import java.util.UUID

internal class PermittedItem(
    private val work: JobWork,
    private val permit: RemotePermit,
) {
    val jobId: UUID
        get() = work.lease.jobId

    fun <T : Any> persist(operation: () -> T): T = work.persist(operation)

    fun completed(reference: String) = work.completeRemote(permit = permit.id, reference = reference)

    fun superseded() = work.completeRemote(permit = permit.id, reference = null, state = ItemState.SUPERSEDED)

    fun failed(reason: String) =
        work.completeRemote(permit = permit.id, reference = null, state = ItemState.FAILED, reason = reason)

    fun retried(reason: String) = work.capacity.rejected(work.lease, permit.id, reason)
}
