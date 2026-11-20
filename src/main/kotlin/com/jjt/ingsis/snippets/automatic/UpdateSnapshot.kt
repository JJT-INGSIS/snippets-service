package com.jjt.ingsis.snippets.automatic

import com.jjt.ingsis.snippets.execution.TestSnapshot
import com.jjt.ingsis.snippets.metadata.ContentReplacementResult
import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.update.UpdateCandidate
import java.util.UUID

data class UpdateSnapshot(
    val jobId: UUID,
    val snippetId: UUID,
    val actorId: String,
    val language: String,
    val version: String,
    val reference: ContentReference,
    val codeHash: String,
    val tests: List<TestSnapshot>,
    val released: Boolean = false,
)

fun interface SnippetUpdateCommit {
    fun commit(
        candidate: UpdateCandidate,
        reference: ContentReference,
    ): ContentReplacementResult
}

interface UpdateSnapshots {
    fun find(jobId: UUID): UpdateSnapshot?

    // Only a durable terminal without an outstanding remote obligation permits release.
    fun release(jobId: UUID): Boolean
}

fun interface ObsoleteContent {
    fun discard(reference: ContentReference)
}
