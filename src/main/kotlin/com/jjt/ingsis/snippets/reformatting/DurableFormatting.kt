package com.jjt.ingsis.snippets.reformatting

import com.jjt.ingsis.snippets.automatic.ObsoleteContent
import com.jjt.ingsis.snippets.background.ItemState
import com.jjt.ingsis.snippets.background.JobStep
import com.jjt.ingsis.snippets.background.JobWork
import com.jjt.ingsis.snippets.background.RemotePermit
import com.jjt.ingsis.snippets.background.RemoteTerminationEvidence
import com.jjt.ingsis.snippets.background.WorkItem
import com.jjt.ingsis.snippets.detail.StableContentRead
import com.jjt.ingsis.snippets.formattingrules.FormattedVariant
import com.jjt.ingsis.snippets.formattingrules.FormattedVariants
import com.jjt.ingsis.snippets.formattingrules.FormattingBasis
import com.jjt.ingsis.snippets.formattingrules.FormattingRulesStore
import com.jjt.ingsis.snippets.formattingrules.FormattingSelection
import com.jjt.ingsis.snippets.formattingrules.VariantPublication
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.operations.ProcessingKind
import com.jjt.ingsis.snippets.language.operations.ProcessingOperation
import com.jjt.ingsis.snippets.processing.OperationPreparation
import com.jjt.ingsis.snippets.processing.ProcessingBasis
import com.jjt.ingsis.snippets.processing.ProcessingContent
import com.jjt.ingsis.snippets.processing.ProcessingReaction
import com.jjt.ingsis.snippets.processing.ProcessingSelection
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StoreResult
import org.springframework.stereotype.Component

@Component
class DurableFormatting(
    private val content: ProcessingContent,
    private val rules: FormattingRulesStore,
    private val language: Language,
    private val storage: SnippetContentStorage,
    private val variants: FormattedVariants,
    private val obsolete: ObsoleteContent,
) : ProcessingReaction {
    override val kind = ProcessingKind.FORMAT

    override fun prepare(
        work: JobWork,
        item: WorkItem,
    ): OperationPreparation {
        val target = ReformattingTarget.of(work.job.event) ?: return OperationPreparation.Failed("INVALID_EVENT_SCOPE")
        val selection = rules.find(target.scope)?.selection ?: FormattingSelection.EMPTY
        if (selection.revision != target.rulesRevision) return OperationPreparation.Superseded
        val processor =
            language.formatProcessor(target.scope.language).verifiedOrNull()
                ?: return OperationPreparation.Retry("LANGUAGE_UNAVAILABLE")
        return when (val read = content.read(item)) {
            is StableContentRead.Read -> {
                if (read.snippet.metadata.language != target.scope.language ||
                    read.snippet.metadata.version != target.scope.version
                ) {
                    OperationPreparation.Superseded
                } else {
                    content.prepare(
                        read,
                        target.scope.ownerId,
                        ProcessingSelection(
                            kind = kind,
                            rules = selection.rules,
                            revision = selection.revision,
                            processor = processor,
                        ),
                    )
                }
            }

            StableContentRead.Unavailable -> {
                OperationPreparation.Retry("STORAGE_UNAVAILABLE")
            }

            StableContentRead.Conflict -> {
                OperationPreparation.Superseded
            }

            StableContentRead.Broken -> {
                OperationPreparation.Failed("CONTENT_BROKEN")
            }
        }
    }

    override fun publish(
        work: JobWork,
        permit: RemotePermit,
        operation: ProcessingOperation,
    ): JobStep {
        val basis = ProcessingBasis.of(permit) ?: return JobStep.Unknown("MISSING_PUBLICATION_BASIS")
        val resultRules =
            operation.command
                ?.rules
                ?.takeIf { it.values.none { value -> value == null } }
                ?.mapValues { checkNotNull(it.value) }
                ?.let { FormattingSelection(it).revision }
        if (!content.matches(basis, operation) ||
            resultRules != basis.rules
        ) {
            return JobStep.Unknown("REMOTE_INPUT_MISMATCH")
        }
        val output = operation.output
        if (output?.outcome != "FORMATTED" || output.code == null) {
            work.completeRemote(
                permit = permit.id,
                reference = null,
                state = ItemState.FAILED,
                reason =
                    operation.reason
                        ?: if (output?.outcome ==
                            "INVALID_CODE"
                        ) {
                            "UNFORMATTABLE"
                        } else {
                            output?.outcome ?: "REMOTE_FAILED"
                        },
            )
            return JobStep.Complete
        }
        val stored = storage.store(output.code)
        if (stored !is StoreResult.Stored) return JobStep.Unknown("STORAGE_UNAVAILABLE")
        val variant =
            FormattedVariant(
                snippetId = basis.snippetId,
                basis =
                    FormattingBasis(
                        content = basis.reference,
                        language = basis.language,
                        version = basis.version,
                        rules = basis.rules,
                        processor = basis.processor,
                    ),
                derived = stored.reference,
            )
        val publication = publishStored(work = work, permit = permit, variant = variant, owner = basis.owner)
        when (publication) {
            is VariantPublication.Published -> publication.replaced?.let(obsolete::discard)
            VariantPublication.Superseded, VariantPublication.CodeChanged -> obsolete.discard(stored.reference)
        }
        return JobStep.Complete
    }

    private fun publishStored(
        work: JobWork,
        permit: RemotePermit,
        variant: FormattedVariant,
        owner: String,
    ): VariantPublication =
        work.persist {
            variants.publish(variant, owner, work.lease.jobId).also {
                when (it) {
                    is VariantPublication.Published -> {
                        work.completeRemote(permit = permit.id, reference = variant.derived.value)
                    }

                    VariantPublication.Superseded -> {
                        work.completeRemote(permit = permit.id, reference = null, state = ItemState.SUPERSEDED)
                    }

                    VariantPublication.CodeChanged -> {
                        work.capacity.retryTerminated(
                            lease = work.lease,
                            permitId = permit.id,
                            evidence = RemoteTerminationEvidence { "OPERATION_COMPLETED:${permit.id}" },
                            reason = "CODE_CHANGED",
                        )
                    }
                }
            }
        }
}
