package com.jjt.ingsis.snippets.reformatting

import com.jjt.ingsis.snippets.automatic.ObsoleteContent
import com.jjt.ingsis.snippets.background.ItemState
import com.jjt.ingsis.snippets.background.JobStep
import com.jjt.ingsis.snippets.background.JobWork
import com.jjt.ingsis.snippets.background.RemotePermit
import com.jjt.ingsis.snippets.background.WorkItem
import com.jjt.ingsis.snippets.formattingrules.FormattedVariant
import com.jjt.ingsis.snippets.formattingrules.FormattedVariants
import com.jjt.ingsis.snippets.formattingrules.OwnerRulesFormatter
import com.jjt.ingsis.snippets.formattingrules.SavedSnippetFormatting
import com.jjt.ingsis.snippets.formattingrules.VariantPublication
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StoreResult
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class SnippetReformatting(
    private val links: SnippetContentLinks,
    private val formatter: OwnerRulesFormatter,
    private val storage: SnippetContentStorage,
    private val variants: FormattedVariants,
    private val obsolete: ObsoleteContent,
) {
    fun process(
        work: JobWork,
        target: ReformattingTarget,
        item: WorkItem,
    ): JobStep {
        val snippet = links.findLinked(UUID.fromString(item.key))
        if (snippet == null) {
            work.complete(item = item, reference = null, state = ItemState.SUPERSEDED)
            return JobStep.Complete
        }
        val permit = work.beginRemote(item) ?: return JobStep.Yield
        val permitted = PermittedItem(work, permit)
        val formatted = formatter.format(snippet, target.scope.ownerId)

        when {
            formatted !is SavedSnippetFormatting.Formatted -> fail(permitted, ReformattingFailures.of(formatted))
            !target.matches(formatted.basis) -> permitted.superseded()
            else -> publish(permitted, target, formatted)
        }
        return JobStep.Complete
    }

    fun release(
        work: JobWork,
        permit: RemotePermit,
    ) = PermittedItem(work, permit).retried("FORMAT_INTERRUPTED")

    private fun publish(
        permitted: PermittedItem,
        target: ReformattingTarget,
        formatted: SavedSnippetFormatting.Formatted,
    ) {
        val stored = storage.store(formatted.code)
        if (stored !is StoreResult.Stored) return permitted.retried("STORAGE_UNAVAILABLE")
        val variant = FormattedVariant(formatted.snippet.id, formatted.basis, stored.reference)
        val publication =
            permitted.persist {
                variants.publish(variant, target.scope.ownerId, permitted.jobId).also { settle(permitted, it, variant) }
            }

        when (publication) {
            is VariantPublication.Published -> publication.replaced?.let(obsolete::discard)
            VariantPublication.CodeChanged, VariantPublication.Superseded -> obsolete.discard(variant.derived)
        }
    }

    private fun settle(
        permitted: PermittedItem,
        publication: VariantPublication,
        variant: FormattedVariant,
    ) = when (publication) {
        is VariantPublication.Published -> permitted.completed(variant.derived.value)
        VariantPublication.CodeChanged -> permitted.retried("CODE_CHANGED")
        VariantPublication.Superseded -> permitted.superseded()
    }

    private fun fail(
        permitted: PermittedItem,
        failure: ReformattingFailure,
    ) = if (failure.definitive) permitted.failed(failure.reason) else permitted.retried(failure.reason)
}
