package com.jjt.ingsis.snippets.background

import java.util.UUID

interface ExecutionCapacity {
    fun held(jobId: UUID): RemotePermit?

    fun begin(
        lease: JobLease,
        itemId: UUID,
    ): RemotePermit?

    fun bind(
        lease: JobLease,
        permitId: UUID,
        remote: RemoteExecution,
    )

    fun complete(
        lease: JobLease,
        permitId: UUID,
        result: String?,
        state: ItemState,
        reason: String? = null,
    )

    fun rejected(
        lease: JobLease,
        permitId: UUID,
        reason: String,
    )

    fun resolve(
        lease: JobLease,
        permitId: UUID,
        evidence: RemoteTerminationEvidence,
    )
}
