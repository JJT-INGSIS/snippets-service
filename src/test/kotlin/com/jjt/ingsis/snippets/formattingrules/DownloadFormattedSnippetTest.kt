package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.access.ReadDecision
import com.jjt.ingsis.snippets.access.ReadFailure
import com.jjt.ingsis.snippets.access.ReadGate
import com.jjt.ingsis.snippets.background.NewBackgroundJob
import com.jjt.ingsis.snippets.detail.ContentReading
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.language.Attributed
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.LanguageValidator
import com.jjt.ingsis.snippets.language.ProcessorIdentity
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.language.ValidationResult
import com.jjt.ingsis.snippets.language.formatting.FormattingCatalogResult
import com.jjt.ingsis.snippets.language.formatting.FormattingCheckResult
import com.jjt.ingsis.snippets.language.formatting.FormattingResult
import com.jjt.ingsis.snippets.language.formatting.LanguageFormatter
import com.jjt.ingsis.snippets.metadata.ContentLinkResult
import com.jjt.ingsis.snippets.metadata.ContentReplacementResult
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
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
private const val ORIGINAL_CODE = "let saludo:string=\"¡Hola, señor! 🚀\";"
private const val STORED_CODE = "let saludo: string = \"¡Hola, señor! 🚀\";"

private val currentProcessor = ProcessorRevision("sha256-v1:" + "e".repeat(64))

private val olderProcessor = ProcessorRevision("sha256-v1:" + "d".repeat(64))

private val downloadedId = UUID.randomUUID()

private val saved =
    SnippetMetadata(
        id = downloadedId,
        name = "Saludo",
        description = null,
        language = "printscript",
        version = "1.1",
        state = SnippetState.CONFIRMED,
    )

private val code = ContentReference("code")

private val editedCode = ContentReference("edited-code")

private val derived = ContentReference("derived")

class DownloadFormattedSnippetTest {
    private val links = EditableLinks(LinkedSnippet(saved, code))

    private val storage =
        StoredBlobs(mapOf(code to ORIGINAL_CODE, editedCode to "println(2);", derived to STORED_CODE))

    private val rules = OwnerRules()

    private val formatter = CountingFormatter()

    private val variants = PublishedVariants()

    private val language = Language(listOf(PrintScriptValidator()), formatters = listOf(formatter))

    private val indentTwo = FormattingSelection(mapOf("indent-inside-if" to 2))

    @Test
    fun `a current stored variant is downloaded without formatting again`() {
        rules.save(OWNER, indentTwo)
        variants.variant = variantFor(code, indentTwo.revision, currentProcessor)

        val download = downloadAs(OWNER, AccessRelation.OWNED) as FormattedDownload.Ready

        assertThat(download.source).isEqualTo(VariantSource.STORED)
        assertThat(download.code).isEqualTo(STORED_CODE)
        assertThat(download.snippet).isEqualTo(saved)
        assertThat(download.extension).isEqualTo("prs")
        assertThat(download.basis).isEqualTo(variants.variant?.basis)
        assertThat(formatter.received).isEmpty()
    }

    @Test
    fun `without a stored variant the saved code is formatted now and nothing is written`() {
        val download = downloadAs(OWNER, AccessRelation.OWNED) as FormattedDownload.Ready

        assertThat(download.source).isEqualTo(VariantSource.ON_DEMAND)
        assertThat(download.code).isEqualTo("$ORIGINAL_CODE|{}")
        assertThat(download.basis.processor).isEqualTo(currentProcessor)
        assertThat(formatter.received).containsExactly(emptyMap())
    }

    @Test
    fun `a reader downloads the same variant as the owner with the rules of the owner`() {
        rules.save(OWNER, indentTwo)
        rules.save(READER, FormattingSelection(mapOf("enforce-spacing-around-equals" to true)))

        val forOwner = downloadAs(OWNER, AccessRelation.OWNED) as FormattedDownload.Ready
        val forReader = downloadAs(READER, AccessRelation.SHARED) as FormattedDownload.Ready

        assertThat(forReader.code).isEqualTo(forOwner.code)
        assertThat(formatter.received).containsOnly(mapOf("indent-inside-if" to 2))
    }

    @Test
    fun `a stored variant of an older base is never presented as current`() {
        val olderBases =
            listOf(
                variantFor(editedCode, FormattingSelection.EMPTY.revision, currentProcessor),
                variantFor(code, indentTwo.revision, currentProcessor),
                variantFor(code, FormattingSelection.EMPTY.revision, olderProcessor),
                variantFor(
                    content = code,
                    rulesRevision = FormattingSelection.EMPTY.revision,
                    processor = currentProcessor,
                    version = "1.0",
                ),
            )

        olderBases.forEach { older ->
            variants.variant = older

            val download = downloadAs(OWNER, AccessRelation.OWNED) as FormattedDownload.Ready

            assertThat(download.source).isEqualTo(VariantSource.ON_DEMAND)
            assertThat(download.code).isEqualTo("$ORIGINAL_CODE|{}")
        }
    }

    @Test
    fun `an unverifiable processor never serves the stored variant`() {
        variants.variant = variantFor(code, FormattingSelection.EMPTY.revision, currentProcessor)
        formatter.revision = ProcessorIdentity.Missing

        assertThat(downloadAs(OWNER, AccessRelation.OWNED))
            .isEqualTo(FormattedDownload.NotReady(SavedSnippetFormatting.LanguageUnavailable))
    }

    @Test
    fun `an edit while the stored variant is read falls back to the current code`() {
        variants.variant = variantFor(code, FormattingSelection.EMPTY.revision, currentProcessor)
        storage.afterRead = { reference -> if (reference == derived) links.linked = LinkedSnippet(saved, editedCode) }

        val download = downloadAs(OWNER, AccessRelation.OWNED) as FormattedDownload.Ready

        assertThat(download.source).isEqualTo(VariantSource.ON_DEMAND)
        assertThat(download.code).isEqualTo("println(2);|{}")
        assertThat(download.basis.content).isEqualTo(editedCode)
    }

    @Test
    fun `a stored variant whose blob is gone is formatted now`() {
        variants.variant =
            variantFor(
                content = code,
                rulesRevision = FormattingSelection.EMPTY.revision,
                processor = currentProcessor,
                blob = ContentReference("gone"),
            )

        val download = downloadAs(OWNER, AccessRelation.OWNED) as FormattedDownload.Ready

        assertThat(download.source).isEqualTo(VariantSource.ON_DEMAND)
    }

    @Test
    fun `a refused read reaches neither storage nor the formatter`() {
        val refusing = downloadWith { _, _ -> ReadDecision.Refused(ReadFailure.SNIPPET_MISSING) }

        assertThat(refusing.download(downloadedId.toString(), ActorContext(listOf(READER))))
            .isEqualTo(FormattedDownload.NotReady(SavedSnippetFormatting.Refused(ReadFailure.SNIPPET_MISSING)))
        assertThat(storage.reads).isEmpty()
        assertThat(formatter.received).isEmpty()
    }

    @Test
    fun `failures to format keep their meaning and the original is never touched`() {
        formatter.answer = { _, _ -> FormattingResult.CodeTooLarge }
        assertThat(downloadAs(OWNER, AccessRelation.OWNED))
            .isEqualTo(FormattedDownload.NotReady(SavedSnippetFormatting.CodeTooLarge))

        storage.available = false
        assertThat(downloadAs(OWNER, AccessRelation.OWNED))
            .isEqualTo(FormattedDownload.NotReady(SavedSnippetFormatting.StorageUnavailable))
    }

    @Test
    fun `an unavailable metadata store is reported instead of a download`() {
        variants.failing = true

        assertThat(downloadAs(OWNER, AccessRelation.OWNED))
            .isEqualTo(FormattedDownload.NotReady(SavedSnippetFormatting.Refused(ReadFailure.METADATA_UNAVAILABLE)))
    }

    private fun downloadAs(
        actor: String,
        relation: AccessRelation,
    ): FormattedDownload {
        val gate = ReadGate { _, _ -> ReadDecision.Granted(links.linked, SnippetAccess(downloadedId, OWNER, relation)) }

        return downloadWith(gate).download(downloadedId.toString(), ActorContext(listOf(actor)))
    }

    private fun downloadWith(gate: ReadGate): DownloadFormattedSnippet {
        val applicable = ApplicableFormattingRules(rules)
        val ownerRules = OwnerRulesFormatter(ContentReading(links, storage), applicable, language)

        return DownloadFormattedSnippet(
            gate = gate,
            stored =
                CurrentStoredVariant(
                    variants = variants,
                    storage = storage,
                    links = links,
                    rules = applicable,
                    language = language,
                ),
            onDemand = FormatSavedSnippet(gate = gate, formatter = ownerRules, links = links, rules = applicable),
            language = language,
        )
    }

    private fun variantFor(
        content: ContentReference,
        rulesRevision: String,
        processor: ProcessorRevision,
        version: String = "1.1",
        blob: ContentReference = derived,
    ): FormattedVariant =
        FormattedVariant(
            snippetId = downloadedId,
            basis =
                FormattingBasis(
                    content = content,
                    language = "printscript",
                    version = version,
                    rules = rulesRevision,
                    processor = processor,
                ),
            derived = blob,
        )

    private class PrintScriptValidator : LanguageValidator {
        override val language: String = "printscript"

        override val extension: String = "prs"

        override val supportedVersions: Set<String> = setOf("1.0", "1.1")

        override fun validate(
            version: String,
            code: String,
        ): ValidationResult = ValidationResult.Valid
    }

    private class CountingFormatter : LanguageFormatter {
        override val language: String = "printscript"

        var revision: ProcessorIdentity = ProcessorIdentity.Verified(currentProcessor)

        var answer: (String, Map<String, Any?>) -> FormattingResult = { code, rules ->
            FormattingResult.Formatted("$code|$rules")
        }

        val received = mutableListOf<Map<String, Any?>>()

        override fun processor(): ProcessorIdentity = revision

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
            return Attributed(answer(code, rules), revision)
        }
    }

    private class PublishedVariants : FormattedVariants {
        var variant: FormattedVariant? = null

        var failing = false

        override fun find(snippetId: UUID): FormattedVariant? =
            if (failing) {
                throw MetadataPersistenceException(IllegalStateException("down"))
            } else {
                variant?.takeIf {
                    it.snippetId ==
                        snippetId
                }
            }

        override fun publish(
            variant: FormattedVariant,
            ownerId: String,
            jobId: UUID,
        ): VariantPublication = error("A download never publishes a variant")
    }

    private class EditableLinks(
        var linked: LinkedSnippet,
    ) : SnippetContentLinks {
        override fun findContent(id: UUID): ContentReference? = linked.reference

        override fun findLinked(id: UUID): LinkedSnippet = linked

        override fun linkContent(
            id: UUID,
            reference: ContentReference,
        ): ContentLinkResult = error("A download never links content")

        override fun replaceContent(
            id: UUID,
            details: SnippetDetails,
            reference: ContentReference,
        ): ContentReplacementResult = error("A download never replaces the original")
    }

    private class StoredBlobs(
        private val blobs: Map<ContentReference, String>,
    ) : SnippetContentStorage {
        var available = true

        var afterRead: (ContentReference) -> Unit = {}

        val reads = mutableListOf<ContentReference>()

        override fun store(code: String): StoreResult = error("A download never stores content")

        override fun read(reference: ContentReference): ReadResult {
            reads.add(reference)
            if (!available) return StorageUnavailable
            val found = blobs[reference]?.let { ReadResult.Found(it) } ?: ReadResult.Missing
            afterRead(reference)
            return found
        }

        override fun delete(reference: ContentReference): DeleteResult = error("A download never deletes content")
    }

    private class OwnerRules : FormattingRulesStore {
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
        ): RulesReplacement = error("A download never changes rules")
    }
}
