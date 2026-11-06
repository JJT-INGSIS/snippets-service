package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.background.NewBackgroundJob
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.lintingrules.LintingRulesChangedJob
import com.jjt.ingsis.snippets.lintingrules.LintingScope
import com.jjt.ingsis.snippets.lintingrules.LintingSelection
import com.jjt.ingsis.snippets.lintingrules.RulesReplacement
import com.jjt.ingsis.snippets.lintingrules.SavedLintingRules
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class)
class LintingRulesPersistenceTest(
    @Autowired private val store: JdbcLintingRules,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val processor = ProcessorRevision("sha256-v1:" + "a".repeat(64))

    private val indentTwo = LintingSelection(mapOf("indent-inside-if" to 2))

    @Test
    fun `a new selection stores configuration revision job and outbox in one commit`() {
        val scope = scope()

        val replacement = store.replace(scope, indentTwo, jobFor = jobFor(scope, indentTwo)) as RulesReplacement.Changed

        assertThat(store.find(scope)).isEqualTo(SavedLintingRules(replacement.configurationId, indentTwo))
        assertThat(revisionOf(scope)).isEqualTo(indentTwo.revision)
        assertThat(jobsOf(scope)).isEqualTo(1)
        assertThat(jobText("scope ->> 'configurationId'", replacement.jobId))
            .isEqualTo(replacement.configurationId.toString())
        assertThat(jobText("revisions ->> 'lintRules'", replacement.jobId)).isEqualTo(indentTwo.revision)
        assertThat(
            jdbc.queryForObject(
                "SELECT envelope ->> 'eventType' FROM event_outbox WHERE job_id = ?",
                String::class.java,
                replacement.jobId,
            ),
        ).isEqualTo("LintRulesChanged")
    }

    @Test
    fun `the same selection again writes no job and a new one keeps the configuration id`() {
        val scope = scope()
        val first = store.replace(scope, indentTwo, jobFor = jobFor(scope, indentTwo)) as RulesReplacement.Changed

        assertThat(
            store.replace(scope, indentTwo, jobFor = jobFor(scope, indentTwo)),
        ).isEqualTo(RulesReplacement.Unchanged)
        assertThat(jobsOf(scope)).isEqualTo(1)

        val indentFour = LintingSelection(mapOf("indent-inside-if" to 4))
        val second = store.replace(scope, indentFour, jobFor = jobFor(scope, indentFour)) as RulesReplacement.Changed

        assertThat(second.configurationId).isEqualTo(first.configurationId)
        assertThat(revisionOf(scope)).isEqualTo(indentFour.revision)
        assertThat(jobsOf(scope)).isEqualTo(2)
    }

    @Test
    fun `an explicit empty selection disables defaults and is durably distinct from absence`() {
        val scope = scope()

        assertThat(store.find(scope)).isNull()
        assertThat(store.replace(scope, LintingSelection.EMPTY, jobFor = jobFor(scope, LintingSelection.EMPTY)))
            .isInstanceOf(RulesReplacement.Changed::class.java)
        assertThat(store.find(scope)?.selection).isEqualTo(LintingSelection.EMPTY)
        assertThat(jobsOf(scope)).isEqualTo(1)
    }

    @Test
    fun `when the job or outbox cannot be written the configuration change is rolled back`() {
        val scope = scope()
        val first = store.replace(scope, indentTwo, jobFor = jobFor(scope, indentTwo)) as RulesReplacement.Changed
        val repeatedEvent = eventIdOf(first.jobId)
        val indentFour = LintingSelection(mapOf("indent-inside-if" to 4))

        assertThatThrownBy {
            store.replace(scope, indentFour) { configurationId ->
                val job =
                    LintingRulesChangedJob.of(
                        scope = scope,
                        configurationId = configurationId,
                        selection = indentFour,
                        processor = processor,
                    )
                job.copy(event = job.event.copy(eventId = repeatedEvent))
            }
        }.isInstanceOf(MetadataPersistenceException::class.java)

        assertThat(store.find(scope)?.selection).isEqualTo(indentTwo)
        assertThat(revisionOf(scope)).isEqualTo(indentTwo.revision)
        assertThat(jobsOf(scope)).isEqualTo(1)
    }

    @Test
    fun `explicitly saving current defaults preserves revision and creates no batch but keeps the choice`() {
        val scope = scope()
        val result =
            store.replace(
                scope = scope,
                selection = indentTwo,
                implicitSelection = indentTwo,
                jobFor = jobFor(scope, indentTwo),
            )
        assertThat(result).isEqualTo(RulesReplacement.Unchanged)
        assertThat(store.find(scope)?.selection).isEqualTo(indentTwo)
        assertThat(jobsOf(scope)).isZero()
    }

    @Test
    fun `disabled false and null values are equivalent to explicit empty selection`() {
        val scope = scope()
        val disabled = LintingSelection(mapOf("flag" to false, "choice" to null))
        assertThat(disabled).isEqualTo(LintingSelection.EMPTY)
        store.replace(scope, disabled, jobFor = jobFor(scope, disabled))
        assertThat(store.replace(scope, LintingSelection.EMPTY, jobFor = jobFor(scope, LintingSelection.EMPTY)))
            .isEqualTo(RulesReplacement.Unchanged)
        assertThat(jobsOf(scope)).isEqualTo(1)
    }

    @Test
    fun `each owner language and version has its own configuration`() {
        val juan = scope()
        val thiago = juan.copy(ownerId = UUID.randomUUID().toString())
        val older = juan.copy(version = "1.0")

        store.replace(juan, indentTwo, jobFor = jobFor(juan, indentTwo))

        assertThat(store.find(thiago)).isNull()
        assertThat(store.find(older)).isNull()
        assertThat(store.find(juan)?.selection).isEqualTo(indentTwo)
    }

    private fun scope(): LintingScope = LintingScope(UUID.randomUUID().toString(), "printscript", "1.1")

    private fun jobFor(
        scope: LintingScope,
        selection: LintingSelection,
    ): (UUID) -> NewBackgroundJob =
        { configurationId ->
            LintingRulesChangedJob.of(
                scope = scope,
                configurationId = configurationId,
                selection = selection,
                processor = processor,
            )
        }

    private fun revisionOf(scope: LintingScope): String? =
        jdbc
            .queryForList(
                "SELECT revision FROM linting_configurations WHERE owner_id = ? AND language = ? AND version = ?",
                String::class.java,
                scope.ownerId,
                scope.language,
                scope.version,
            ).singleOrNull()

    private fun jobsOf(scope: LintingScope): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM background_jobs j JOIN event_outbox o ON o.job_id = j.id " +
                "WHERE j.scope ->> 'ownerId' = ?",
            Int::class.java,
            scope.ownerId,
        ) ?: 0

    private fun jobText(
        expression: String,
        jobId: UUID,
    ): String? = jdbc.queryForObject("SELECT $expression FROM background_jobs WHERE id = ?", String::class.java, jobId)

    private fun eventIdOf(jobId: UUID): UUID =
        jdbc.queryForObject("SELECT event_id FROM background_jobs WHERE id = ?", UUID::class.java, jobId)!!
}
