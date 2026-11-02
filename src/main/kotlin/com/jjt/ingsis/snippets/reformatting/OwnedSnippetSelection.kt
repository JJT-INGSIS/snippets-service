package com.jjt.ingsis.snippets.reformatting

import com.jjt.ingsis.snippets.background.JobWork
import com.jjt.ingsis.snippets.background.NewJobItem
import com.jjt.ingsis.snippets.background.WorkerSettings
import com.jjt.ingsis.snippets.permissions.AccessLister
import com.jjt.ingsis.snippets.permissions.AccessListing
import com.jjt.ingsis.snippets.permissions.RelationFilter
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class OwnedSnippetSelection(
    private val access: AccessLister,
    private val candidates: ReformattingCandidates,
    private val settings: WorkerSettings,
) {
    fun selectPage(
        work: JobWork,
        target: ReformattingTarget,
    ): Boolean {
        val listing = access.list(target.scope.ownerId, RelationFilter.OWNED)
        if (listing !is AccessListing.Listed) return false
        val owned = listing.accesses.map { it.snippetId }.toSet()
        val after = work.job.cursor?.let(UUID::fromString)
        val page = candidates.page(owned = owned, scope = target.scope, after = after, limit = settings.itemBatchSize)
        val items = page.map { candidate -> itemOf(work, target, candidate) }
        val cursor = page.lastOrNull()?.id?.toString() ?: work.job.cursor

        work.select(items, cursor = cursor, complete = page.size < settings.itemBatchSize)
        return true
    }

    private fun itemOf(
        work: JobWork,
        target: ReformattingTarget,
        candidate: ReformattingCandidate,
    ): NewJobItem =
        NewJobItem(
            id = UUID.randomUUID(),
            jobId = work.lease.jobId,
            key = candidate.id.toString(),
            revisions = mapOf("code" to candidate.reference.value, "formattingRules" to target.rulesRevision),
        )
}
