package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.background.ConfirmedJobChange
import com.jjt.ingsis.snippets.background.newJob
import com.jjt.ingsis.snippets.formattingrules.FormattedVariant
import com.jjt.ingsis.snippets.formattingrules.FormattingBasis
import com.jjt.ingsis.snippets.formattingrules.FormattingSelection
import com.jjt.ingsis.snippets.formattingrules.VariantPublication
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.storage.ContentReference
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
class FormattedVariantsPersistenceTest(
    @Autowired private val variants: JdbcFormattedVariants,
    @Autowired private val metadata: SnippetMetadataStore,
    @Autowired private val links: SnippetContentLinks,
    @Autowired private val atomic: AtomicJobWrite,
    @Autowired private val transaction: MetadataTransaction,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val processor = ProcessorRevision("sha256-v1:" + "c".repeat(64))

    private val owner = "dev-" + UUID.randomUUID()

    @Test
    fun `a current variant is published and a newer one replaces it`() {
        val snippet = confirmedSnippet()
        val job = jobId()
        val first = variantOf(snippet, ContentReference("derived-1"))

        assertThat(publish(first, job)).isEqualTo(VariantPublication.Published(replaced = null))
        assertThat(variants.find(snippet)).isEqualTo(first)

        val second = first.copy(derived = ContentReference("derived-2"))

        val replacement = publish(second, job)

        assertThat(replacement).isEqualTo(VariantPublication.Published(replaced = ContentReference("derived-1")))
        assertThat(variants.find(snippet)).isEqualTo(second)
    }

    @Test
    fun `an edited snippet asks for the current code and keeps the published variant`() {
        val snippet = confirmedSnippet()
        val job = jobId()
        val published = variantOf(snippet, ContentReference("derived-1"))
        publish(published, job)
        val old = published.copy(derived = ContentReference("derived-2"))
        replaceContent(snippet, "1.1")

        assertThat(publish(old, job)).isEqualTo(VariantPublication.CodeChanged)
        assertThat(variants.find(snippet)).isEqualTo(published)
    }

    @Test
    fun `newer rules or a new language version supersede the variant`() {
        val snippet = confirmedSnippet()
        val job = jobId()
        val variant = variantOf(snippet, ContentReference("derived-1"))
        saveRules(FormattingSelection(mapOf("indent-inside-if" to 4)))

        assertThat(publish(variant, job)).isEqualTo(VariantPublication.Superseded)

        val other = confirmedSnippet()
        val beforeEdit = variantOf(other, ContentReference("derived-2"))
        replaceContent(other, "1.0")

        assertThat(publish(beforeEdit, job)).isEqualTo(VariantPublication.Superseded)
        assertThat(variants.find(snippet)).isNull()
        assertThat(variants.find(other)).isNull()
    }

    @Test
    fun `publishing needs the caller transaction and rolls back with it`() {
        val snippet = confirmedSnippet()
        val variant = variantOf(snippet, ContentReference("derived-1"))
        val job = jobId()

        assertThatThrownBy { variants.publish(variant, owner, job) }.isInstanceOf(RuntimeException::class.java)
        assertThatThrownBy {
            transaction.commit<Unit> {
                variants.publish(variant, owner, job)
                error("The job turn failed after publishing")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(variants.find(snippet)).isNull()
    }

    private fun publish(
        variant: FormattedVariant,
        job: UUID,
    ): VariantPublication = transaction.commit { variants.publish(variant, owner, job) }

    private fun variantOf(
        snippet: UUID,
        derived: ContentReference,
    ): FormattedVariant {
        val linked = checkNotNull(links.findLinked(snippet))
        val basis = checkNotNull(FormattingBasis.of(linked, FormattingSelection.EMPTY, processor))
        return FormattedVariant(snippetId = snippet, basis = basis, derived = derived)
    }

    private fun confirmedSnippet(): UUID {
        val request = creationRequest().let { it.copy(details = it.details.copy(version = "1.1")) }
        metadata.prepareCreation(request)
        val id = jdbc.queryForObject("SELECT id FROM snippets WHERE creation_key = ?", UUID::class.java, request.key)
        checkNotNull(id)
        links.linkContent(id, ContentReference(UUID.randomUUID().toString()))
        return checkNotNull(metadata.confirmCreation(id)).id
    }

    private fun replaceContent(
        snippet: UUID,
        version: String,
    ) {
        val details = SnippetDetails(name = "Example", description = null, language = "printscript", version = version)
        links.replaceContent(snippet, details, ContentReference(UUID.randomUUID().toString()))
    }

    private fun saveRules(selection: FormattingSelection) {
        jdbc.update(
            "INSERT INTO formatting_configurations (id, owner_id, language, version, selection, revision, " +
                "updated_at) VALUES (?, ?, 'printscript', '1.1', CAST(? AS jsonb), ?, CURRENT_TIMESTAMP)",
            UUID.randomUUID(),
            owner,
            "{}",
            selection.revision,
        )
    }

    private fun jobId(): UUID {
        val job = newJob()
        atomic.commit { ConfirmedJobChange(Unit, job) }
        return job.event.jobId
    }
}
