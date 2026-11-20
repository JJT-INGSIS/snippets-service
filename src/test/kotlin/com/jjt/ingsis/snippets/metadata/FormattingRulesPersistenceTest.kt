package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.background.NewBackgroundJob
import com.jjt.ingsis.snippets.formattingrules.FormattingRulesChangedJob
import com.jjt.ingsis.snippets.formattingrules.FormattingScope
import com.jjt.ingsis.snippets.formattingrules.FormattingSelection
import com.jjt.ingsis.snippets.formattingrules.RulesReplacement
import com.jjt.ingsis.snippets.formattingrules.SavedFormattingRules
import com.jjt.ingsis.snippets.language.ProcessorRevision
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
class FormattingRulesPersistenceTest(
    @Autowired private val store: JdbcFormattingRules,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val processor = ProcessorRevision("sha256-v1:" + "a".repeat(64))

    private val indentTwo = FormattingSelection(mapOf("indent-inside-if" to 2))

    @Test
    fun `a new selection stores configuration revision job and outbox in one commit`() {
        val scope = scope()

        val replacement = store.replace(scope, indentTwo, jobFor(scope, indentTwo)) as RulesReplacement.Changed

        assertThat(store.find(scope)).isEqualTo(SavedFormattingRules(replacement.configurationId, indentTwo))
        assertThat(revisionOf(scope)).isEqualTo(indentTwo.revision)
        assertThat(jobsOf(scope)).isEqualTo(1)
        assertThat(jobText("scope ->> 'configurationId'", replacement.jobId))
            .isEqualTo(replacement.configurationId.toString())
        assertThat(jobText("revisions ->> 'formattingRules'", replacement.jobId)).isEqualTo(indentTwo.revision)
        assertThat(
            jdbc.queryForObject(
                "SELECT envelope ->> 'eventType' FROM event_outbox WHERE job_id = ?",
                String::class.java,
                replacement.jobId,
            ),
        ).isEqualTo("FormattingRulesChanged")
    }

    @Test
    fun `the same selection again writes no job and a new one keeps the configuration id`() {
        val scope = scope()
        val first = store.replace(scope, indentTwo, jobFor(scope, indentTwo)) as RulesReplacement.Changed

        assertThat(store.replace(scope, indentTwo, jobFor(scope, indentTwo))).isEqualTo(RulesReplacement.Unchanged)
        assertThat(jobsOf(scope)).isEqualTo(1)

        val indentFour = FormattingSelection(mapOf("indent-inside-if" to 4))
        val second = store.replace(scope, indentFour, jobFor(scope, indentFour)) as RulesReplacement.Changed

        assertThat(second.configurationId).isEqualTo(first.configurationId)
        assertThat(revisionOf(scope)).isEqualTo(indentFour.revision)
        assertThat(jobsOf(scope)).isEqualTo(2)
    }

    @Test
    fun `an empty selection without saved rules creates no row and no job`() {
        val scope = scope()

        assertThat(store.replace(scope, FormattingSelection.EMPTY, jobFor(scope, FormattingSelection.EMPTY)))
            .isEqualTo(RulesReplacement.Unchanged)
        assertThat(store.find(scope)).isNull()
        assertThat(jobsOf(scope)).isZero()
    }

    @Test
    fun `when the job or outbox cannot be written the configuration change is rolled back`() {
        val scope = scope()
        val first = store.replace(scope, indentTwo, jobFor(scope, indentTwo)) as RulesReplacement.Changed
        val repeatedEvent = eventIdOf(first.jobId)
        val indentFour = FormattingSelection(mapOf("indent-inside-if" to 4))

        assertThatThrownBy {
            store.replace(scope, indentFour) { configurationId ->
                val job =
                    FormattingRulesChangedJob.of(
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
    fun `each owner language and version has its own configuration`() {
        val juan = scope()
        val thiago = juan.copy(ownerId = UUID.randomUUID().toString())
        val older = juan.copy(version = "1.0")

        store.replace(juan, indentTwo, jobFor(juan, indentTwo))

        assertThat(store.find(thiago)).isNull()
        assertThat(store.find(older)).isNull()
        assertThat(store.find(juan)?.selection).isEqualTo(indentTwo)
    }

    private fun scope(): FormattingScope = FormattingScope(UUID.randomUUID().toString(), "printscript", "1.1")

    private fun jobFor(
        scope: FormattingScope,
        selection: FormattingSelection,
    ): (UUID) -> NewBackgroundJob =
        { configurationId ->
            FormattingRulesChangedJob.of(
                scope = scope,
                configurationId = configurationId,
                selection = selection,
                processor = processor,
            )
        }

    private fun revisionOf(scope: FormattingScope): String? =
        jdbc
            .queryForList(
                "SELECT revision FROM formatting_configurations WHERE owner_id = ? AND language = ? AND version = ?",
                String::class.java,
                scope.ownerId,
                scope.language,
                scope.version,
            ).singleOrNull()

    private fun jobsOf(scope: FormattingScope): Int =
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
