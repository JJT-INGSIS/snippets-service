package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.lintingrules.LintAssessment
import com.jjt.ingsis.snippets.lintingrules.LintState
import com.jjt.ingsis.snippets.lintingrules.LintingRulesChangedJob
import com.jjt.ingsis.snippets.lintingrules.LintingScope
import com.jjt.ingsis.snippets.lintingrules.LintingSelection
import com.jjt.ingsis.snippets.storage.ContentReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class)
class LintAssessmentPersistenceTest(
    @Autowired private val assessments: JdbcLintAssessments,
    @Autowired private val configurations: JdbcLintingRules,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val processor = ProcessorRevision("sha256-v1:" + "a".repeat(64))

    @Test
    fun `an older blob cannot replace the current lint projection but its attempt remains recorded`() {
        val basis = snippet()
        assessments.record(basis)
        val first = current(basis.snippetId)
        jdbc.update("UPDATE snippets SET content_reference = 'newer' WHERE id = ?", basis.snippetId)
        assessments.record(basis.copy(state = LintState.COMPLIANT))
        assertThat(current(basis.snippetId)).isEqualTo(first)
        assertThat(attempts(basis.snippetId)).isEqualTo(2)
    }

    @Test
    fun `rules committed during evaluation prevent older results from becoming current`() {
        val basis = snippet()
        val scope = LintingScope(basis.ownerId, basis.language, basis.version)
        val first = LintingSelection(mapOf("fixture-rule" to true))
        val next = LintingSelection(emptyMap())
        save(scope, first)
        assessments.record(basis.copy(rulesRevision = first.revision))
        val visible = current(basis.snippetId)
        save(scope, next)
        assessments.record(basis.copy(rulesRevision = first.revision, state = LintState.COMPLIANT))
        assertThat(current(basis.snippetId)).isEqualTo(visible)
        assessments.record(basis.copy(rulesRevision = next.revision, state = LintState.COMPLIANT))
        assertThat(current(basis.snippetId)).isNotEqualTo(visible)
    }

    @Test
    fun `missing defaults and technical failures persist as unassessed rather than compliance`() {
        val basis = snippet()
        assessments.record(basis)
        val state =
            jdbc.queryForObject(
                "SELECT state FROM snippet_lint_assessments WHERE id = ?",
                String::class.java,
                current(basis.snippetId),
            )
        assertThat(state).isEqualTo("NOT_EVALUATED_ERROR")
    }

    private fun snippet(): LintAssessment {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO snippets (id, name, language, version, state, creation_key, creation_actor_id, " +
                "creation_fingerprint, content_reference) VALUES (?, 'lint', 'printscript', '1.1', " +
                "'CONFIRMED', ?, 'dev-thiago', 'fixture', 'original')",
            id,
            UUID.randomUUID(),
        )
        return LintAssessment(
            snippetId = id,
            ownerId = id.toString(),
            language = "printscript",
            version = "1.1",
            reference = ContentReference("original"),
            codeHash = "sha256-v1:" + "b".repeat(64),
            rulesRevision = null,
            processor = null,
            state = LintState.NOT_EVALUATED_ERROR,
            diagnostics = emptyList(),
            reason = "DEFAULTS_CONTRACT_UNAVAILABLE",
        )
    }

    private fun save(
        scope: LintingScope,
        selection: LintingSelection,
    ) = configurations.replace(scope, selection) { id ->
        LintingRulesChangedJob.of(scope = scope, configurationId = id, selection = selection, processor = processor)
    }

    private fun current(id: UUID): UUID? =
        jdbc.queryForObject(
            "SELECT current_lint_assessment FROM snippets WHERE id = ?",
            UUID::class.java,
            id,
        )

    private fun attempts(id: UUID): Int? =
        jdbc.queryForObject(
            "SELECT count(*) FROM snippet_lint_assessments WHERE snippet_id = ?",
            Int::class.java,
            id,
        )
}
