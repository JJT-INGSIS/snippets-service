package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.access.ReadDecision
import com.jjt.ingsis.snippets.access.ReadFailure
import com.jjt.ingsis.snippets.access.ReadGate
import com.jjt.ingsis.snippets.background.NewBackgroundJob
import com.jjt.ingsis.snippets.detail.ContentReading
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.language.Attributed
import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.LanguageValidator
import com.jjt.ingsis.snippets.language.ProcessorIdentity
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.language.ValidationResult
import com.jjt.ingsis.snippets.language.formatting.FormattingCatalogResult
import com.jjt.ingsis.snippets.language.formatting.FormattingCheckResult
import com.jjt.ingsis.snippets.language.formatting.FormattingResult
import com.jjt.ingsis.snippets.language.formatting.FormattingRuleViolation
import com.jjt.ingsis.snippets.language.formatting.LanguageFormatter
import com.jjt.ingsis.snippets.metadata.ContentLinkResult
import com.jjt.ingsis.snippets.metadata.ContentReplacementResult
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetDetails
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.permissions.AccessRelation
import com.jjt.ingsis.snippets.permissions.SnippetAccess
import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.DeleteResult
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StorageUnavailable
import com.jjt.ingsis.snippets.storage.StoreResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

private const val OWNER = "dev-juan"
private const val READER = "dev-thiago"

private val formatProcessor = ProcessorRevision("sha256-v1:" + "e".repeat(64))

private val snippetId = UUID.randomUUID()

private val metadata =
    SnippetMetadata(
        id = snippetId,
        name = "Example",
        description = null,
        language = "printscript",
        version = "1.1",
        state = SnippetState.CONFIRMED,
    )

private val first = ContentReference("first")

private val second = ContentReference("second")

class FormatSavedSnippetTest {
    private val links = MutableLinks(LinkedSnippet(metadata, first))

    private val storage = SavedCodes(mapOf(first to "println(1);", second to "println(2);"))

    private val rules = SavedRules()

    private val formatter = RecordingFormatter()

    private val language = Language(listOf(PrintScriptOnly()), formatters = listOf(formatter))

    private val indentTwo = FormattingSelection(mapOf("indent-inside-if" to 2))

    @Test
    fun `the owner gets the saved code formatted with their rules and nothing is written`() {
        rules.save(OWNER, indentTwo)

        val outcome = formatAs(OWNER, AccessRelation.OWNED)

        assertThat(outcome).isEqualTo(
            SavedSnippetFormatting.Formatted(
                snippet = metadata,
                code = "println(1);|{indent-inside-if=2}",
                basis =
                    FormattingBasis(
                        content = first,
                        language = "printscript",
                        version = "1.1",
                        rules = indentTwo.revision,
                        processor = formatProcessor,
                    ),
            ),
        )
    }

    @Test
    fun `a reader gets the variant formatted with the rules of the owner and never their own`() {
        rules.save(OWNER, indentTwo)
        rules.save(READER, FormattingSelection(mapOf("enforce-spacing-around-equals" to true)))

        val outcome = formatAs(READER, AccessRelation.SHARED)

        assertThat(formatter.received).containsExactly(mapOf("indent-inside-if" to 2))
        assertThat((outcome as SavedSnippetFormatting.Formatted).basis.rules).isEqualTo(indentTwo.revision)
    }

    @Test
    fun `without saved rules the snippet is formatted with the defaults of the catalog`() {
        val outcome = formatAs(OWNER, AccessRelation.OWNED)

        assertThat(formatter.received).containsExactly(emptyMap())
        assertThat((outcome as SavedSnippetFormatting.Formatted).basis.rules)
            .isEqualTo(FormattingSelection.EMPTY.revision)
    }

    @Test
    fun `an edit during formatting is retried and the result belongs to the current code`() {
        formatter.beforeAnswer = { if (formatter.received.size == 1) links.linked = LinkedSnippet(metadata, second) }

        val outcome = formatAs(OWNER, AccessRelation.OWNED) as SavedSnippetFormatting.Formatted

        assertThat(outcome.code).isEqualTo("println(2);|{}")
        assertThat(outcome.basis.content).isEqualTo(second)
        assertThat(formatter.received).hasSize(2)
    }

    @Test
    fun `rules that keep changing never publish an obsolete result`() {
        formatter.beforeAnswer = {
            rules.save(OWNER, FormattingSelection(mapOf("indent-inside-if" to formatter.received.size)))
        }

        assertThat(formatAs(OWNER, AccessRelation.OWNED)).isEqualTo(SavedSnippetFormatting.Stale)
        assertThat(formatter.received).hasSize(2)
    }

    @Test
    fun `an unverifiable processor or a failed call is never presented as a current variant`() {
        formatter.processor = ProcessorIdentity.Missing
        assertThat(formatAs(OWNER, AccessRelation.OWNED)).isEqualTo(SavedSnippetFormatting.LanguageUnavailable)

        formatter.processor = ProcessorIdentity.Verified(formatProcessor)
        formatter.answer = { _, _ -> FormattingResult.Failed("down") }
        assertThat(formatAs(OWNER, AccessRelation.OWNED)).isEqualTo(SavedSnippetFormatting.LanguageUnavailable)
    }

    @Test
    fun `typed formatting failures keep their meaning`() {
        val diagnostic = Diagnostic(rule = "UNTERMINATED_STRING", message = "falta cerrar", line = 1, column = 9)
        val violation = FormattingRuleViolation("indent-inside-if", "out-of-range")
        val expected =
            mapOf(
                FormattingResult.Unformattable(listOf(diagnostic)) to
                    SavedSnippetFormatting.Unformattable(listOf(diagnostic)),
                FormattingResult.InvalidRules(listOf(violation)) to
                    SavedSnippetFormatting.InvalidRules(listOf(violation)),
                FormattingResult.CodeTooLarge to SavedSnippetFormatting.CodeTooLarge,
                FormattingResult.OutputTooLarge to SavedSnippetFormatting.OutputTooLarge,
            )

        expected.forEach { (result, outcome) ->
            formatter.answer = { _, _ -> result }
            assertThat(formatAs(OWNER, AccessRelation.OWNED)).isEqualTo(outcome)
        }
    }

    @Test
    fun `a refused read or unavailable code is reported without formatting`() {
        val refusing =
            FormatSavedSnippet(
                gate = { _, _ -> ReadDecision.Refused(ReadFailure.SNIPPET_MISSING) },
                content = ContentReading(links, storage),
                links = links,
                rules = ApplicableFormattingRules(rules),
                language = language,
            )
        assertThat(refusing.format(snippetId.toString(), ActorContext()))
            .isEqualTo(SavedSnippetFormatting.Refused(ReadFailure.SNIPPET_MISSING))

        storage.available = false
        assertThat(formatAs(OWNER, AccessRelation.OWNED)).isEqualTo(SavedSnippetFormatting.StorageUnavailable)
        assertThat(formatter.received).isEmpty()
    }

    private fun formatAs(
        actor: String,
        relation: AccessRelation,
    ): SavedSnippetFormatting {
        val gate =
            ReadGate { _, _ -> ReadDecision.Granted(links.linked, SnippetAccess(snippetId, OWNER, relation)) }
        val useCase =
            FormatSavedSnippet(
                gate = gate,
                content = ContentReading(links, storage),
                links = links,
                rules = ApplicableFormattingRules(rules),
                language = language,
            )
        return useCase.format(snippetId.toString(), ActorContext(listOf(actor)))
    }

    private class PrintScriptOnly : LanguageValidator {
        override val language: String = "printscript"

        override val extension: String = "prs"

        override val supportedVersions: Set<String> = setOf("1.0", "1.1")

        override fun validate(
            version: String,
            code: String,
        ): ValidationResult = ValidationResult.Valid
    }

    private class RecordingFormatter : LanguageFormatter {
        override val language: String = "printscript"

        var processor: ProcessorIdentity = ProcessorIdentity.Verified(formatProcessor)

        var beforeAnswer: () -> Unit = {}

        var answer: (String, Map<String, Any?>) -> FormattingResult = { code, rules ->
            FormattingResult.Formatted("$code|$rules")
        }

        val received = mutableListOf<Map<String, Any?>>()

        override fun catalog(version: String): Attributed<FormattingCatalogResult> = error("Not used")

        override fun check(
            version: String,
            rules: Map<String, Any?>,
        ): Attributed<FormattingCheckResult> = error("Not used")

        override fun format(
            version: String,
            code: String,
            rules: Map<String, Any?>,
        ): Attributed<FormattingResult> {
            received.add(rules)
            beforeAnswer()
            return Attributed(answer(code, rules), processor)
        }
    }

    private class MutableLinks(
        var linked: LinkedSnippet,
    ) : SnippetContentLinks {
        override fun findContent(id: UUID): ContentReference? = linked.reference

        override fun findLinked(id: UUID): LinkedSnippet = linked

        override fun linkContent(
            id: UUID,
            reference: ContentReference,
        ): ContentLinkResult = error("Formatting never links content")

        override fun replaceContent(
            id: UUID,
            details: SnippetDetails,
            reference: ContentReference,
        ): ContentReplacementResult = error("Formatting never replaces the original")
    }

    private class SavedCodes(
        private val codes: Map<ContentReference, String>,
    ) : SnippetContentStorage {
        var available = true

        override fun store(code: String): StoreResult = error("Formatting never stores content")

        override fun read(reference: ContentReference): ReadResult =
            if (available) ReadResult.Found(codes.getValue(reference)) else StorageUnavailable

        override fun delete(reference: ContentReference): DeleteResult = error("Formatting never deletes content")
    }

    private class SavedRules : FormattingRulesStore {
        private val saved = mutableMapOf<String, FormattingSelection>()

        fun save(
            ownerId: String,
            selection: FormattingSelection,
        ) {
            saved[ownerId] = selection
        }

        override fun find(scope: FormattingScope): SavedFormattingRules? =
            saved[scope.ownerId]?.let { SavedFormattingRules(UUID.randomUUID(), it) }

        override fun replace(
            scope: FormattingScope,
            selection: FormattingSelection,
            jobFor: (configurationId: UUID) -> NewBackgroundJob,
        ): RulesReplacement = error("Formatting never changes rules")
    }
}
