package com.jjt.ingsis.snippets.lintingrules

import com.jjt.ingsis.snippets.background.ItemState
import com.jjt.ingsis.snippets.background.JobStep
import com.jjt.ingsis.snippets.background.JobWork
import com.jjt.ingsis.snippets.background.RemotePermit
import com.jjt.ingsis.snippets.background.WorkItem
import com.jjt.ingsis.snippets.detail.StableContentRead
import com.jjt.ingsis.snippets.language.operations.ProcessingKind
import com.jjt.ingsis.snippets.language.operations.ProcessingOperation
import com.jjt.ingsis.snippets.processing.OperationPreparation
import com.jjt.ingsis.snippets.processing.ProcessingBasis
import com.jjt.ingsis.snippets.processing.ProcessingContent
import com.jjt.ingsis.snippets.processing.ProcessingReaction
import com.jjt.ingsis.snippets.processing.ProcessingSelection
import org.springframework.stereotype.Component

@Component
class DurableLinting(
    private val content: ProcessingContent,
    private val rules: LintingRulesStore,
    private val provider: LintingRulesProvider,
    private val assessments: LintAssessments,
) : ProcessingReaction {
    override val kind = ProcessingKind.LINT

    override fun prepare(
        work: JobWork,
        item: WorkItem,
    ): OperationPreparation {
        val scope = work.job.event.lintScope() ?: return OperationPreparation.Failed("INVALID_EVENT_SCOPE")
        val selection = rules.find(scope)?.selection ?: return OperationPreparation.Superseded
        if (selection.revision != work.job.event.revisions["lintRules"]) return OperationPreparation.Superseded
        val catalog =
            provider.catalog(scope) as? CatalogLookup.Found ?: return OperationPreparation.Retry("LINT_UNAVAILABLE")
        val processor = catalog.processor ?: return OperationPreparation.Retry("PROCESSOR_UNVERIFIABLE")
        if (processor.value != work.job.event.revisions["lintProcessor"]) return OperationPreparation.Superseded
        return when (val read = content.read(item)) {
            is StableContentRead.Read -> {
                if (read.snippet.metadata.language != scope.language ||
                    read.snippet.metadata.version != scope.version
                ) {
                    OperationPreparation.Superseded
                } else {
                    content.prepare(
                        read,
                        scope.ownerId,
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
        val resultRules = operation.command?.rules?.let { LintingSelection(it).revision }
        if (!content.matches(basis, operation) ||
            resultRules != basis.rules
        ) {
            return JobStep.Unknown("REMOTE_INPUT_MISMATCH")
        }
        val output = operation.output
        val analyzed = output?.outcome == "LINTED"
        val assessment =
            LintAssessment(
                snippetId = basis.snippetId,
                ownerId = basis.owner,
                language = basis.language,
                version = basis.version,
                reference = basis.reference,
                codeHash = basis.codeHash,
                rulesRevision = basis.rules,
                processor = basis.processor.value,
                state =
                    if (!analyzed) {
                        LintState.NOT_EVALUATED_ERROR
                    } else if (output.diagnostics.isEmpty()) {
                        LintState.COMPLIANT
                    } else {
                        LintState.NON_COMPLIANT
                    },
                diagnostics = output?.diagnostics.orEmpty(),
                reason =
                    if (analyzed) {
                        null
                    } else {
                        operation.reason
                            ?: output?.outcome
                            ?: "REMOTE_FAILED"
                    },
            )
        work.persist {
            if (content.current(basis)) {
                assessments.record(assessment)
                work.completeRemote(
                    permit = permit.id,
                    reference = null,
                    state = if (analyzed) ItemState.COMPLETED else ItemState.FAILED,
                    reason = assessment.reason,
                )
            } else {
                work.completeRemote(permit = permit.id, reference = null, state = ItemState.SUPERSEDED)
            }
        }
        return JobStep.Complete
    }
}
