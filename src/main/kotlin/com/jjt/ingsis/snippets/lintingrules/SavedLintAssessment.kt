package com.jjt.ingsis.snippets.lintingrules

import com.jjt.ingsis.snippets.creation.SnippetDraft
import com.jjt.ingsis.snippets.execution.SnapshotFingerprint
import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.linting.LintResult
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

enum class LintState { COMPLIANT, NON_COMPLIANT, NOT_EVALUATED_ERROR }

data class LintAssessment(
    val snippetId: UUID,
    val ownerId: String,
    val language: String,
    val version: String,
    val reference: ContentReference,
    val codeHash: String,
    val rulesRevision: String?,
    val processor: String?,
    val state: LintState,
    val diagnostics: List<Diagnostic>,
    val reason: String?,
)

fun interface LintAssessments {
    // Saves the attempt; only replaces the current projection while code/rules remain the same.
    fun record(assessment: LintAssessment)
}

fun interface SavedSnippetLint {
    fun assess(
        id: UUID,
        ownerId: String,
        draft: SnippetDraft,
    )
}

@Component
class SavedLintAssessment(
    private val links: SnippetContentLinks,
    private val storage: SnippetContentStorage,
    private val configurations: LintingRulesStore,
    private val language: Language,
    private val fingerprints: SnapshotFingerprint,
    private val assessments: LintAssessments,
) : SavedSnippetLint {
    private val logger = LoggerFactory.getLogger(SavedLintAssessment::class.java)

    override fun assess(
        id: UUID,
        ownerId: String,
        draft: SnippetDraft,
    ) {
        try {
            val linked = links.findLinked(id) ?: return
            val reference = linked.reference ?: return
            // An intervening update must not associate an earlier request's code with its new blob.
            val content = storage.read(reference)
            if (content is ReadResult.Found && content.code != draft.code) return
            val configuration = configurations.find(LintingScope(ownerId, draft.language, draft.version))
            val basis =
                LintAssessment(
                    snippetId = id,
                    ownerId = ownerId,
                    language = draft.language,
                    version = draft.version,
                    reference = reference,
                    codeHash = fingerprints.code(draft.language, draft.version, draft.code),
                    rulesRevision = configuration?.selection?.revision,
                    processor = null,
                    state = LintState.NOT_EVALUATED_ERROR,
                    diagnostics = emptyList(),
                    reason = null,
                )
            val evaluated =
                when {
                    content !is ReadResult.Found -> basis.copy(reason = "STORAGE_UNAVAILABLE")
                    configuration == null -> basis.copy(reason = "DEFAULTS_CONTRACT_UNAVAILABLE")
                    else -> evaluate(basis, draft, configuration.selection)
                }
            assessments.record(evaluated)
        } catch (_: MetadataPersistenceException) {
            // The content commit already succeeded; reporting lint must never turn it into a failed save.
            logger.warn("Lint assessment could not be persisted for confirmed snippet {}", id)
        }
    }

    private fun evaluate(
        basis: LintAssessment,
        draft: SnippetDraft,
        selection: LintingSelection,
    ): LintAssessment {
        val answer =
            language.lint(
                language = draft.language,
                version = draft.version,
                code = draft.code,
                rules = selection.rules,
            )
        val processor =
            answer.processor.verifiedOrNull()
                ?: return basis.copy(reason = "PROCESSOR_UNVERIFIABLE")
        return when (val result = answer.result) {
            is LintResult.Analyzed -> {
                basis.copy(
                    processor = processor.value,
                    state = if (result.compliant) LintState.COMPLIANT else LintState.NON_COMPLIANT,
                    diagnostics = result.diagnostics,
                )
            }

            is LintResult.InvalidCode -> {
                basis.copy(
                    processor = processor.value,
                    reason = "CODE_NOT_ANALYZABLE",
                    diagnostics = result.diagnostics,
                )
            }

            is LintResult.InvalidRules -> {
                basis.copy(processor = processor.value, reason = "RULES_NO_LONGER_VALID")
            }

            LintResult.CodeTooLarge -> {
                basis.copy(processor = processor.value, reason = "CODE_TOO_LARGE")
            }

            LintResult.DiagnosticsTooLarge -> {
                basis.copy(processor = processor.value, reason = "DIAGNOSTICS_TOO_LARGE")
            }

            is LintResult.UnsupportedLanguage -> {
                basis.copy(processor = processor.value, reason = "UNSUPPORTED_LANGUAGE")
            }

            is LintResult.UnsupportedVersion -> {
                basis.copy(processor = processor.value, reason = "UNSUPPORTED_VERSION")
            }

            is LintResult.Failed -> {
                basis.copy(processor = processor.value, reason = "LINT_UNAVAILABLE")
            }
        }
    }
}
