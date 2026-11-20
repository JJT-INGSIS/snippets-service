package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.background.EventType
import com.jjt.ingsis.snippets.background.NewBackgroundJob
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.language.Attributed
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.LanguageValidator
import com.jjt.ingsis.snippets.language.ProcessorIdentity
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.language.ValidationResult
import com.jjt.ingsis.snippets.language.formatting.FormattingCatalogResult
import com.jjt.ingsis.snippets.language.formatting.FormattingCheckResult
import com.jjt.ingsis.snippets.language.formatting.FormattingResult
import com.jjt.ingsis.snippets.language.formatting.FormattingRule
import com.jjt.ingsis.snippets.language.formatting.FormattingRuleViolation
import com.jjt.ingsis.snippets.language.formatting.LanguageFormatter
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

private val revision = ProcessorRevision("sha256-v1:" + "f".repeat(64))

private val spacing =
    FormattingRule(
        id = "enforce-spacing-around-equals",
        type = "boolean",
        default = false,
        minimum = null,
        maximum = null,
        conflictsWith = listOf("enforce-no-spacing-around-equals"),
        description = "Un espacio alrededor de cada =.",
    )

private val indent =
    FormattingRule(
        id = "indent-inside-if",
        type = "integer",
        default = null,
        minimum = 0,
        maximum = 10,
        conflictsWith = emptyList(),
        description = "Espacios de sangría dentro de un if.",
    )

class FormattingRulesUseCasesTest {
    private val formatter = ScriptedFormatter()

    private val store = MemoryFormattingRules()

    private val identity =
        ActorIdentityResolver { context ->
            context.developmentActorHeaders.singleOrNull()?.let { ActorIdentity.Identified(it) }
                ?: ActorIdentity.Missing
        }

    private val provider = FormattingRulesProvider(Language(listOf(PrintScriptOnly()), formatters = listOf(formatter)))

    private val read = ReadFormattingRules(identity, provider, store)

    private val save = SaveFormattingRules(identity, provider, store)

    private val juan = ActorContext(listOf("dev-juan"))

    private val thiago = ActorContext(listOf("dev-thiago"))

    @Test
    fun `without saved rules the owner sees the real defaults of the catalog`() {
        val view = shown(reading())

        assertThat(view.revision).isEqualTo(FormattingSelection.EMPTY.revision)
        assertThat(view.changed).isFalse()
        assertThat(view.jobId).isNull()
        assertThat(view.rules).containsExactly(ConfiguredRule(spacing, false), ConfiguredRule(indent, null))
    }

    @Test
    fun `saving new rules keeps them and leaves one job with the agreed event`() {
        val rules = mapOf("enforce-spacing-around-equals" to true, "indent-inside-if" to 2)

        val view = shown(saving(rules))

        val job = store.jobs.single()
        val configuration = store.saved.values.single()
        assertThat(view.changed).isTrue()
        assertThat(view.jobId).isEqualTo(job.event.jobId)
        assertThat(view.rules.map { it.value }).containsExactly(true, 2)
        assertThat(job.event.eventType).isEqualTo(EventType.FormattingRulesChanged)
        assertThat(job.event.scope).isEqualTo(
            mapOf(
                "ownerId" to "dev-juan",
                "language" to "printscript",
                "version" to "1.1",
                "configurationId" to configuration.configurationId.toString(),
            ),
        )
        assertThat(job.event.revisions)
            .isEqualTo(mapOf("formattingRules" to view.revision, "formatProcessor" to revision.value))
        assertThat(job.reaction).isEqualTo("format-owned-snippets")
        assertThat(job.initiatedBy).isEqualTo("dev-juan")
        assertThat(shown(reading()).rules.map { it.value }).containsExactly(true, 2)
    }

    @Test
    fun `saving the same rules again changes nothing and leaves no other job`() {
        val rules = mapOf<String, Any?>("indent-inside-if" to 2)
        saving(rules)

        val again = shown(saving(rules))

        assertThat(again.changed).isFalse()
        assertThat(again.jobId).isNull()
        assertThat(store.jobs).hasSize(1)
    }

    @Test
    fun `an empty or all default selection without saved rules writes nothing`() {
        val selections =
            listOf(emptyMap(), mapOf("enforce-spacing-around-equals" to false, "indent-inside-if" to null))

        selections.forEach { rules ->
            val view = shown(saving(rules))

            assertThat(view.changed).isFalse()
            assertThat(view.revision).isEqualTo(FormattingSelection.EMPTY.revision)
        }
        assertThat(store.saved).isEmpty()
        assertThat(store.jobs).isEmpty()
    }

    @Test
    fun `going back to the defaults after saving rules is a real change`() {
        saving(mapOf("indent-inside-if" to 2))

        val view = shown(saving(emptyMap()))

        assertThat(view.changed).isTrue()
        assertThat(view.revision).isEqualTo(FormattingSelection.EMPTY.revision)
        assertThat(store.jobs).hasSize(2)
    }

    @Test
    fun `invalid rules keep the previous configuration`() {
        saving(mapOf("indent-inside-if" to 2))
        val violation = FormattingRuleViolation("indent-inside-if", "out-of-range")
        formatter.answer = { FormattingCheckResult.InvalidRules(listOf(violation)) }

        val outcome = saving(mapOf("indent-inside-if" to 11))

        val kept = store.saved.values.single()
        assertThat(outcome).isEqualTo(FormattingRulesOutcome.InvalidRules(listOf(violation)))
        assertThat(kept.selection).isEqualTo(FormattingSelection(mapOf("indent-inside-if" to 2)))
        assertThat(store.jobs).hasSize(1)
    }

    @Test
    fun `an unavailable or unverifiable provider keeps the previous configuration`() {
        val rules = mapOf<String, Any?>("indent-inside-if" to 2)

        formatter.answer = { FormattingCheckResult.Failed("down") }
        assertThat(saving(rules)).isEqualTo(FormattingRulesOutcome.LanguageUnavailable)

        formatter.answer = ScriptedFormatter.ACCEPT
        formatter.processor = ProcessorIdentity.Missing
        assertThat(saving(rules)).isEqualTo(FormattingRulesOutcome.LanguageUnavailable)

        formatter.processor = ProcessorIdentity.Verified(revision)
        formatter.catalogProcessor = ProcessorIdentity.Verified(ProcessorRevision("sha256-v1:" + "0".repeat(64)))
        assertThat(saving(rules)).isEqualTo(FormattingRulesOutcome.LanguageUnavailable)

        assertThat(store.saved).isEmpty()
        assertThat(store.jobs).isEmpty()
    }

    @Test
    fun `an unsupported language or version reaches neither the provider nor the store`() {
        assertThat(saving(emptyMap(), language = "python", version = "3.12"))
            .isEqualTo(FormattingRulesOutcome.UnsupportedLanguage("python"))
        assertThat(saving(emptyMap(), version = "9.9"))
            .isEqualTo(FormattingRulesOutcome.UnsupportedVersion("printscript", "9.9"))
        assertThat(reading(version = "9.9"))
            .isEqualTo(FormattingRulesOutcome.UnsupportedVersion("printscript", "9.9"))
        assertThat(formatter.checked).isEmpty()
    }

    @Test
    fun `each identity reads and changes only its own rules`() {
        saving(mapOf("indent-inside-if" to 2))

        assertThat(shown(reading(thiago)).rules.map { it.value }).containsExactly(false, null)

        saving(mapOf("enforce-spacing-around-equals" to true), thiago)

        assertThat(shown(reading()).rules.map { it.value }).containsExactly(false, 2)
        assertThat(store.jobs.map { it.event.scope["ownerId"] }).containsExactly("dev-juan", "dev-thiago")
    }

    @Test
    fun `without identity nothing is read or saved`() {
        assertThat(reading(ActorContext()))
            .isEqualTo(FormattingRulesOutcome.IdentityRejected)
        assertThat(saving(mapOf("indent-inside-if" to 2), ActorContext()))
            .isEqualTo(FormattingRulesOutcome.IdentityRejected)
        assertThat(formatter.checked).isEmpty()
    }

    @Test
    fun `a store failure is reported and nothing is published`() {
        store.failing = true

        assertThat(saving(mapOf("indent-inside-if" to 2)))
            .isEqualTo(FormattingRulesOutcome.MetadataUnavailable)
        assertThat(reading()).isEqualTo(FormattingRulesOutcome.MetadataUnavailable)
        assertThat(store.jobs).isEmpty()
    }

    private fun saving(
        rules: Map<String, Any?>,
        context: ActorContext = juan,
        language: String = "printscript",
        version: String = "1.1",
    ): FormattingRulesOutcome = save.save(language = language, version = version, rules = rules, context = context)

    private fun reading(
        context: ActorContext = juan,
        language: String = "printscript",
        version: String = "1.1",
    ): FormattingRulesOutcome = read.read(language, version, context)

    private fun shown(outcome: FormattingRulesOutcome): FormattingRulesView =
        (outcome as FormattingRulesOutcome.Shown).view

    private class PrintScriptOnly : LanguageValidator {
        override val language: String = "printscript"

        override val extension: String = "prs"

        override val supportedVersions: Set<String> = setOf("1.0", "1.1")

        override fun validate(
            version: String,
            code: String,
        ): ValidationResult = ValidationResult.Valid
    }

    private class ScriptedFormatter : LanguageFormatter {
        override val language: String = "printscript"

        var processor: ProcessorIdentity = ProcessorIdentity.Verified(revision)

        var catalogProcessor: ProcessorIdentity? = null

        var answer: (Map<String, Any?>) -> FormattingCheckResult = ACCEPT

        val checked = mutableListOf<Map<String, Any?>>()

        override fun catalog(version: String): Attributed<FormattingCatalogResult> =
            Attributed(FormattingCatalogResult.Catalog(listOf(spacing, indent)), catalogProcessor ?: processor)

        override fun check(
            version: String,
            rules: Map<String, Any?>,
        ): Attributed<FormattingCheckResult> {
            checked.add(rules)
            return Attributed(answer(rules), processor)
        }

        override fun format(
            version: String,
            code: String,
            rules: Map<String, Any?>,
        ): Attributed<FormattingResult> = error("Saving rules never formats code")

        companion object {
            val ACCEPT: (Map<String, Any?>) -> FormattingCheckResult = { rules ->
                FormattingCheckResult.Accepted(
                    rules.mapNotNull { (id, value) -> value?.takeIf { it != false }?.let { id to it } }.toMap(),
                )
            }
        }
    }

    private class MemoryFormattingRules : FormattingRulesStore {
        val saved = mutableMapOf<FormattingScope, SavedFormattingRules>()

        val jobs = mutableListOf<NewBackgroundJob>()

        var failing = false

        override fun find(scope: FormattingScope): SavedFormattingRules? {
            failIfAsked()
            return saved[scope]
        }

        override fun replace(
            scope: FormattingScope,
            selection: FormattingSelection,
            jobFor: (configurationId: UUID) -> NewBackgroundJob,
        ): RulesReplacement {
            failIfAsked()
            val current = saved[scope]
            if ((current?.selection ?: FormattingSelection.EMPTY) == selection) return RulesReplacement.Unchanged
            val configurationId = current?.configurationId ?: UUID.randomUUID()
            val job = jobFor(configurationId)
            saved[scope] = SavedFormattingRules(configurationId, selection)
            jobs.add(job)
            return RulesReplacement.Changed(configurationId, job.event.jobId)
        }

        private fun failIfAsked() {
            if (failing) throw MetadataPersistenceException(IllegalStateException("PostgreSQL is down"))
        }
    }
}
