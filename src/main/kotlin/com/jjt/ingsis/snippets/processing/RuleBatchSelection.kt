package com.jjt.ingsis.snippets.processing

import com.jjt.ingsis.snippets.background.JobWork
import com.jjt.ingsis.snippets.background.NewJobItem
import com.jjt.ingsis.snippets.background.WorkerSettings
import com.jjt.ingsis.snippets.permissions.AccessLister
import com.jjt.ingsis.snippets.permissions.AccessListing
import com.jjt.ingsis.snippets.permissions.RelationFilter
import com.jjt.ingsis.snippets.storage.ContentReference
import org.springframework.stereotype.Component
import java.util.UUID

data class RuleBatchScope(
    val ownerId: String,
    val language: String,
    val version: String,
)

data class RuleBatchCandidate(
    val id: UUID,
    val reference: ContentReference,
)

fun interface RuleBatchCandidates {
    fun page(
        owned: Set<UUID>,
        scope: RuleBatchScope,
        after: UUID?,
        limit: Int,
    ): List<RuleBatchCandidate>
}

@Component
class RuleBatchSelection(
    private val access: AccessLister,
    private val candidates: RuleBatchCandidates,
    private val settings: WorkerSettings,
) {
    fun select(
        work: JobWork,
        scope: RuleBatchScope,
        revisionKey: String,
        revision: String,
    ): Boolean {
        val listing = access.list(scope.ownerId, RelationFilter.OWNED)
        if (listing !is AccessListing.Listed) return false
        val page =
            candidates.page(
                owned = listing.accesses.map { it.snippetId }.toSet(),
                scope = scope,
                after = work.job.cursor?.let(UUID::fromString),
                limit = settings.itemBatchSize,
            )
        val items =
            page.map {
                NewJobItem(
                    id = UUID.randomUUID(),
                    jobId = work.lease.jobId,
                    key = it.id.toString(),
                    revisions = mapOf("code" to it.reference.value, revisionKey to revision),
                )
            }
        work.select(
            items,
            cursor = page.lastOrNull()?.id?.toString() ?: work.job.cursor,
            complete = page.size < settings.itemBatchSize,
        )
        return true
    }
}
