package com.jjt.ingsis.snippets.automatic

import com.jjt.ingsis.snippets.access.ReadDecision
import com.jjt.ingsis.snippets.access.ReadFailure
import com.jjt.ingsis.snippets.access.ReadGate
import com.jjt.ingsis.snippets.background.ItemState
import com.jjt.ingsis.snippets.background.JobProgress
import com.jjt.ingsis.snippets.execution.AuditedRun
import com.jjt.ingsis.snippets.execution.Comparison
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.language.execution.ExecutionState
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import org.springframework.stereotype.Service
import java.util.UUID

enum class AutomaticValidity { CURRENT, SUPERSEDED, UNVERIFIABLE }

data class AutomaticTestResult(
    val testId: UUID,
    val state: ItemState?,
    val outcome: ExecutionState?,
    val comparison: Comparison?,
    val outputs: List<String>,
    val reason: String?,
)

data class AutomaticTestsView(
    val revision: UUID?,
    val progress: JobProgress?,
    val validity: AutomaticValidity,
    val tests: List<AutomaticTestResult>,
)

data class AutomaticItemProgress(
    val testId: UUID,
    val state: ItemState,
    val reason: String?,
)

data class CurrentAutomaticSnapshot(
    val update: UpdateSnapshot,
    val progress: JobProgress,
    val runs: List<AuditedRun>,
    val current: Boolean,
    val items: List<AutomaticItemProgress>,
)

fun interface AutomaticResults {
    // Short database-only projection, holding the snippet lock used by update/test creation.
    fun current(snippetId: UUID): CurrentAutomaticSnapshot?
}

sealed interface AutomaticLookup {
    data class Found(
        val view: AutomaticTestsView,
    ) : AutomaticLookup

    data class Refused(
        val failure: ReadFailure,
    ) : AutomaticLookup

    data object Unavailable : AutomaticLookup
}

@Service
class CurrentAutomaticTests(
    private val gate: ReadGate,
    private val results: AutomaticResults,
    private val language: Language,
) {
    fun find(
        snippetId: String,
        context: ActorContext,
    ): AutomaticLookup =
        when (val access = gate.open(snippetId, context)) {
            is ReadDecision.Refused -> AutomaticLookup.Refused(access.failure)
            is ReadDecision.Granted -> read(access.snippet.metadata.id)
        }

    private fun read(id: UUID): AutomaticLookup =
        try {
            val result = results.current(id)
            if (result == null) {
                AutomaticLookup.Found(
                    AutomaticTestsView(
                        revision = null,
                        progress = null,
                        validity = AutomaticValidity.CURRENT,
                        tests = emptyList(),
                    ),
                )
            } else {
                val processor =
                    if (result.runs.any { it.remote != null }) {
                        currentProcessor(
                            result.update.language,
                        )
                    } else {
                        null
                    }
                // Re-read after remote I/O; a code/test change must not publish an older projection as current.
                val confirmed = results.current(id)
                if (confirmed == null || confirmed.update.jobId != result.update.jobId) {
                    AutomaticLookup.Found(
                        AutomaticTestsView(
                            revision = null,
                            progress = null,
                            validity = AutomaticValidity.SUPERSEDED,
                            tests = emptyList(),
                        ),
                    )
                } else {
                    AutomaticLookup.Found(project(confirmed, processor))
                }
            }
        } catch (_: MetadataPersistenceException) {
            AutomaticLookup.Unavailable
        }

    private fun currentProcessor(languageId: String): ProcessorRevision? {
        val selected = language.executorFor(languageId)
        if (selected !is ExecutionResult.Success) return null
        return (selected.value.processorRevision() as? ExecutionResult.Success)?.value
    }

    private fun project(
        result: CurrentAutomaticSnapshot,
        processor: ProcessorRevision?,
    ): AutomaticTestsView {
        val validity =
            when {
                !result.current -> AutomaticValidity.SUPERSEDED

                result.runs.any { it.remote != null && it.remote.processor == null } -> AutomaticValidity.UNVERIFIABLE

                result.runs.any {
                    it.remote != null && processor != null && it.remote.processor != processor
                } -> AutomaticValidity.SUPERSEDED

                result.runs.any { it.remote != null } && processor == null -> AutomaticValidity.UNVERIFIABLE

                else -> AutomaticValidity.CURRENT
            }
        val visible =
            if (validity == AutomaticValidity.CURRENT) {
                result.update.tests.map { test ->
                    val run = result.runs.find { it.snapshot.test?.id == test.id }
                    val item = result.items.find { it.testId == test.id }
                    AutomaticTestResult(
                        testId = test.id,
                        state = item?.state,
                        outcome = run?.completed?.terminal?.outcome,
                        comparison = run?.completed?.comparison,
                        outputs = run?.completed?.outputs.orEmpty(),
                        reason = run?.failure ?: run?.completed?.terminal?.reason ?: item?.reason,
                    )
                }
            } else {
                emptyList()
            }
        return AutomaticTestsView(
            revision = result.update.jobId,
            progress = result.progress,
            validity = validity,
            tests = visible,
        )
    }
}
