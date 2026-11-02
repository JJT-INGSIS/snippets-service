package com.jjt.ingsis.snippets.background

import java.util.UUID

class JobWork(
    val job: StoredJob,
    private val store: JobProgressStore,
    val capacity: ExecutionCapacity,
) {
    val lease: JobLease = checkNotNull(job.lease)
    private var selected = false
    private var claimed = false
    private var started = false

    // Database-only revision checks, result and progress share this fenced commit; no HTTP here.
    fun <T : Any> persist(operation: () -> T): T = store.persist(lease, operation)

    fun select(
        items: List<NewJobItem>,
        cursor: String?,
        complete: Boolean,
    ) {
        check(!selected) { "Yield between selection pages" }
        selected = true
        store.select(lease = lease, items = items, cursor = cursor, complete = complete)
    }

    fun items(): List<WorkItem> {
        if (claimed) return emptyList()
        claimed = true
        return store.items(lease)
    }

    fun complete(
        item: WorkItem,
        reference: String?,
        state: ItemState = ItemState.COMPLETED,
    ) = store.completeItem(lease = lease, itemId = item.id, result = reference, state = state)

    fun retry(
        item: WorkItem,
        reason: String,
    ) = store.retryItem(lease, item.id, reason)

    fun beginRemote(item: WorkItem): RemotePermit? {
        if (started) return null
        return capacity.begin(lease, item.id)?.also { started = true }
    }

    fun bindRemote(
        permit: UUID,
        remote: RemoteExecution,
    ) = capacity.bind(lease, permit, remote)

    fun completeRemote(
        permit: UUID,
        reference: String?,
        state: ItemState = ItemState.COMPLETED,
    ) = capacity.complete(lease = lease, permitId = permit, result = reference, state = state)
}
