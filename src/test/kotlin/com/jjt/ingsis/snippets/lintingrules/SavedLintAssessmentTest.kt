package com.jjt.ingsis.snippets.lintingrules

import com.jjt.ingsis.snippets.creation.draft
import com.jjt.ingsis.snippets.language.Attributed
import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.ProcessorIdentity
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.language.linting.LintResult
import com.jjt.ingsis.snippets.metadata.JsonSnapshotFingerprint
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

class SavedLintAssessmentTest {
    private val id = UUID.randomUUID()
    private val draft = draft()
    private val owner = "dev-thiago"
    private val reference = ContentReference("fixture-code")
    private val links = mock(SnippetContentLinks::class.java)
    private val storage = mock(SnippetContentStorage::class.java)
    private val configurations = mock(LintingRulesStore::class.java)
    private val language = mock(Language::class.java)
    private val selection = LintingSelection(mapOf("fixture-rule" to true))
    private val processor = ProcessorIdentity.Verified(ProcessorRevision("sha256-v1:" + "a".repeat(64)))
    private val recorded = mutableListOf<LintAssessment>()

    private fun service(saved: LintingSelection? = selection): SavedLintAssessment {
        val metadata =
            SnippetMetadata(
                id = id,
                name = draft.name,
                description = draft.description,
                language = draft.language,
                version = draft.version,
                state = SnippetState.CONFIRMED,
            )
        `when`(links.findLinked(id)).thenReturn(LinkedSnippet(metadata, reference))
        `when`(storage.read(reference)).thenReturn(ReadResult.Found(draft.code))
        `when`(configurations.find(LintingScope(owner, draft.language, draft.version)))
            .thenReturn(saved?.let { SavedLintingRules(UUID.randomUUID(), it) })
        return SavedLintAssessment(
            links = links,
            storage = storage,
            configurations = configurations,
            language = language,
            fingerprints = JsonSnapshotFingerprint(),
            assessments =
                LintAssessments {
                    recorded +=
                        it
                },
        )
    }

    @Test
    fun `provider violations are recorded without rejecting confirmed content`() {
        val service = service()
        val diagnostic = Diagnostic(rule = "fixture-rule", message = "style", line = 1, column = 2)
        `when`(
            language.lint(
                language = draft.language,
                version = draft.version,
                code = draft.code,
                rules = selection.rules,
            ),
        ).thenReturn(Attributed(LintResult.Analyzed(listOf(diagnostic)), processor))
        service.assess(id, owner, draft)
        assertThat(recorded.single().state).isEqualTo(LintState.NON_COMPLIANT)
        assertThat(recorded.single().diagnostics).containsExactly(diagnostic)
        assertThat(recorded.single().processor).isEqualTo(processor.revision.value)
    }

    @Test
    fun `technical failure is not compliance or an infringement`() {
        val service = service()
        `when`(
            language.lint(
                language = draft.language,
                version = draft.version,
                code = draft.code,
                rules = selection.rules,
            ),
        ).thenReturn(Attributed(LintResult.Failed("down"), ProcessorIdentity.Missing))
        service.assess(id, owner, draft)
        assertThat(recorded.single().state).isEqualTo(LintState.NOT_EVALUATED_ERROR)
        assertThat(recorded.single().diagnostics).isEmpty()
    }

    @Test
    fun `missing provider defaults remain an explicit blocked capability and never invented success`() {
        service(null).assess(id, owner, draft)
        assertThat(recorded.single().reason).isEqualTo("DEFAULTS_CONTRACT_UNAVAILABLE")
        assertThat(recorded.single().state).isEqualTo(LintState.NOT_EVALUATED_ERROR)
    }

    @Test
    fun `an intervening update cannot associate previous code with the newer blob`() {
        val service = service()
        `when`(storage.read(reference)).thenReturn(ReadResult.Found("different"))
        service.assess(id, owner, draft)
        assertThat(recorded).isEmpty()
    }

    @Test
    fun `metadata failure after the content commit cannot escape into a failed save`() {
        val service = service()
        `when`(links.findLinked(id)).thenThrow(MetadataPersistenceException(IllegalStateException("down")))
        service.assess(id, owner, draft)
        assertThat(recorded).isEmpty()
    }

    @Test
    fun `explicit empty configuration is sent as empty rather than falling back to defaults`() {
        val service = service(LintingSelection.EMPTY)
        `when`(language.lint(language = draft.language, version = draft.version, code = draft.code, rules = emptyMap()))
            .thenReturn(Attributed(LintResult.Analyzed(emptyList()), processor))
        service.assess(id, owner, draft)
        assertThat(recorded.single().state).isEqualTo(LintState.COMPLIANT)
        assertThat(recorded.single().rulesRevision).isEqualTo(LintingSelection.EMPTY.revision)
    }
}
