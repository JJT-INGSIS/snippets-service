package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.lintingrules.LintAssessment
import com.jjt.ingsis.snippets.lintingrules.LintState
import com.jjt.ingsis.snippets.lintingrules.LintingRulesChangedJob
import com.jjt.ingsis.snippets.lintingrules.LintingScope
import com.jjt.ingsis.snippets.lintingrules.LintingSelection
import com.jjt.ingsis.snippets.lintquery.DetailLint
import com.jjt.ingsis.snippets.lintquery.LanguageVersion
import com.jjt.ingsis.snippets.lintquery.LintFinding
import com.jjt.ingsis.snippets.lintquery.LintReadBasis
import com.jjt.ingsis.snippets.lintquery.LintStatus
import com.jjt.ingsis.snippets.storage.ContentReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

private data class LintFixture(
    val linked: LinkedSnippet,
    val owner: String,
) {
    val id = linked.metadata.id
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class)
class LintQueryPersistenceTest(
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val search: JdbcSnippetSearch,
    @Autowired private val detail: JdbcLintDetail,
    @Autowired private val assessments: JdbcLintAssessments,
    @Autowired private val configurations: JdbcLintingRules,
) {
    private val processor = ProcessorRevision("sha256-v1:" + "a".repeat(64))
    private val diagnostic = Diagnostic(rule = "fixture-rule", message = "ñ", line = 2, column = 3)

    @Test
    fun `compliance filter and count are applied before pagination across authorized owners`() {
        val passed = List(3) { fixture("same") }
        val rejected = List(2) { fixture("same") }
        passed.forEach { record(it, LintState.COMPLIANT) }
        rejected.forEach { record(it, LintState.NON_COMPLIANT) }
        val all = passed + rejected
        val pages = (0..2).map { find(all, LintStatus.NON_COMPLIANT, it) }
        assertThat(pages.map { it.count }).containsOnly(2L)
        assertThat(pages.flatMap { it.snippets }.map { it.id })
            .containsExactlyElementsOf(rejected.map { it.id }.sortedBy { it.toString() })
        assertThat(pages.last().snippets).isEmpty()
        assertThat(
            pages
                .first()
                .lint.values
                .single()
                .diagnostics,
        ).containsExactly(diagnostic)
    }

    @Test
    fun `new content hides earlier diagnostics and hashes without reading any blob`() {
        val fixture = fixture()
        record(fixture, LintState.NON_COMPLIANT)
        jdbc.update("UPDATE snippets SET content_reference = 'new-content' WHERE id = ?", fixture.id)
        val view = find(listOf(fixture)).lint.getValue(fixture.id)
        assertThat(view).isEqualTo(LintFinding.SUPERSEDED)
        assertThat(view.diagnostics).isEmpty()
        assertThat(view.codeHash).isNull()
    }

    @Test
    fun `new rules or processor hide the previously valid result`() {
        val rulesChanged = fixture()
        val processorChanged = fixture()
        record(rulesChanged, LintState.COMPLIANT)
        record(processorChanged, LintState.NON_COMPLIANT)
        save(rulesChanged, LintingSelection(mapOf("fixture-rule" to true)))
        assertThat(find(listOf(rulesChanged)).lint.getValue(rulesChanged.id)).isEqualTo(LintFinding.SUPERSEDED)
        val replacement = ProcessorRevision("sha256-v1:" + "b".repeat(64))
        val found = find(listOf(processorChanged), expected = replacement)
        assertThat(found.lint.getValue(processorChanged.id)).isEqualTo(LintFinding.SUPERSEDED)
    }

    @Test
    fun `missing evaluation and technical failure are distinct from noncompliance`() {
        val pending = fixture()
        val failed = fixture()
        val rejected = fixture()
        record(failed, LintState.NOT_EVALUATED_ERROR)
        record(rejected, LintState.NON_COMPLIANT)
        val rows = find(listOf(pending, failed, rejected), size = 10)
        assertThat(rows.snippets.map { rows.lint.getValue(it.id).status })
            .containsExactly(LintStatus.FAILED, LintStatus.NON_COMPLIANT, LintStatus.PENDING)
        assertThat(rows.lint.getValue(pending.id)).isEqualTo(LintFinding.NOT_EVALUATED)
        assertThat(rows.lint.getValue(failed.id).reason).isEqualTo("LINT_UNAVAILABLE")
    }

    @Test
    fun `the owner supplied by Permissions determines validity rather than the creator audit field`() {
        val fixture = fixture()
        record(fixture, LintState.COMPLIANT)
        assertThat(find(listOf(fixture)).lint.getValue(fixture.id).status).isEqualTo(LintStatus.COMPLIANT)
        val changedOwner = fixture.copy(owner = "different-owner")
        assertThat(find(listOf(changedOwner)).lint.getValue(fixture.id)).isEqualTo(LintFinding.SUPERSEDED)
    }

    @Test
    fun `an unverified scope appearing after processor lookup cannot become a fictitious empty filtered page`() {
        val fixture = fixture()
        record(fixture, LintState.COMPLIANT)
        val query =
            SnippetFilter(
                name = "",
                language = "",
                lintStatus = LintStatus.COMPLIANT,
                lintBasis = LintReadBasis(emptyMap(), mapOf(fixture.id to fixture.owner)),
            )
        val result =
            search.search(
                ids = setOf(fixture.id),
                filter = query,
                order = SnippetOrder(SortField.NAME, SortDirection.ASC),
                page = PageRequest(0, 10),
            )
        assertThat(result.lintVerified).isFalse()
    }

    @Test
    fun `detail does not attach newer diagnostics to code read before a concurrent update`() {
        val fixture = fixture()
        record(fixture, LintState.NON_COMPLIANT)
        jdbc.update("UPDATE snippets SET content_reference = 'new-content' WHERE id = ?", fixture.id)
        assertThat(detail.find(fixture.linked, basis(listOf(fixture))))
            .isEqualTo(DetailLint.Found(LintFinding.SUPERSEDED))
    }

    @Test
    fun `detail and list use exactly the same current result`() {
        val fixture = fixture()
        record(fixture, LintState.NON_COMPLIANT)
        val page = find(listOf(fixture))
        assertThat(detail.find(fixture.linked, basis(listOf(fixture))))
            .isEqualTo(DetailLint.Found(page.lint.getValue(fixture.id)))
    }

    private fun fixture(name: String = "fixture"): LintFixture {
        val id = UUID.randomUUID()
        val owner = UUID.randomUUID().toString()
        val reference = ContentReference("fixture-$id")
        jdbc.update(
            "INSERT INTO snippets (id, name, language, version, state, creation_key, creation_actor_id, " +
                "creation_fingerprint, content_reference) VALUES (?, ?, 'printscript', '1.1', " +
                "'CONFIRMED', ?, 'unrelated-creator', 'fixture', ?)",
            id,
            name,
            UUID.randomUUID(),
            reference.value,
        )
        val metadata =
            SnippetMetadata(
                id = id,
                name = name,
                description = null,
                language = "printscript",
                version = "1.1",
                state = SnippetState.CONFIRMED,
            )
        val fixture = LintFixture(LinkedSnippet(metadata, reference), owner)
        save(fixture, LintingSelection.EMPTY)
        return fixture
    }

    private fun save(
        fixture: LintFixture,
        selection: LintingSelection,
    ) {
        val scope = LintingScope(fixture.owner, "printscript", "1.1")
        configurations.replace(scope, selection) { id ->
            LintingRulesChangedJob.of(scope = scope, configurationId = id, selection = selection, processor = processor)
        }
    }

    private fun record(
        fixture: LintFixture,
        state: LintState,
    ) = assessments.record(
        LintAssessment(
            snippetId = fixture.id,
            ownerId = fixture.owner,
            language = "printscript",
            version = "1.1",
            reference = checkNotNull(fixture.linked.reference),
            codeHash = "sha256-v1:" + "c".repeat(64),
            rulesRevision = LintingSelection.EMPTY.revision,
            processor = processor.value,
            state = state,
            diagnostics = if (state == LintState.NON_COMPLIANT) listOf(diagnostic) else emptyList(),
            reason = if (state == LintState.NOT_EVALUATED_ERROR) "LINT_UNAVAILABLE" else null,
        ),
    )

    private fun basis(
        fixtures: List<LintFixture>,
        expected: ProcessorRevision = processor,
    ) = LintReadBasis(
        mapOf(LanguageVersion("printscript", "1.1") to expected),
        fixtures.associate { it.id to it.owner },
    )

    private fun find(
        fixtures: List<LintFixture>,
        status: LintStatus? = null,
        page: Int = 0,
        size: Int = 1,
        expected: ProcessorRevision = processor,
    ): MetadataPage =
        search.search(
            ids = fixtures.map { it.id }.toSet(),
            filter =
                SnippetFilter(
                    name = "",
                    language = "",
                    lintStatus = status,
                    lintBasis = basis(fixtures, expected),
                ),
            order = SnippetOrder(SortField.LINT, SortDirection.ASC),
            page = PageRequest(page, size),
        )
}
