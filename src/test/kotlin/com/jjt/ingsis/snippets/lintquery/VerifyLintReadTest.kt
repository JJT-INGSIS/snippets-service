package com.jjt.ingsis.snippets.lintquery

import com.jjt.ingsis.snippets.language.Attributed
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.ProcessorIdentity
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.language.linting.LintCatalogResult
import com.jjt.ingsis.snippets.lintingrules.LintingSelection
import com.jjt.ingsis.snippets.metadata.SnippetFilter
import com.jjt.ingsis.snippets.permissions.AccessRelation
import com.jjt.ingsis.snippets.permissions.SnippetAccess
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.UUID

class VerifyLintReadTest {
    private val language = mock(Language::class.java)
    private val scope = LanguageVersion("printscript", "1.1")
    private val processor = ProcessorRevision("sha256-v1:" + "a".repeat(64))
    private val access = SnippetAccess(UUID.randomUUID(), "owner", AccessRelation.SHARED)
    private val filter = SnippetFilter("", "")

    @Test
    fun `current defaults use the same canonical effective selection as saved assessments`() {
        val defaults = mapOf("identifier_format" to "camel case", "disabled" to false)
        `when`(language.lintingCatalog("printscript", "1.1"))
            .thenReturn(
                Attributed(LintCatalogResult.Catalog(emptyList(), defaults), ProcessorIdentity.Verified(processor)),
            )
        val result =
            VerifyLintRead(MetadataLanguages { _, _ -> setOf(scope) }, language)
                .prepare(listOf(access), filter) as PreparedLintRead.Ready
        assertThat(result.basis.defaults).isEqualTo(mapOf(scope to LintingSelection(defaults).revision))
    }

    @Test
    fun `many snippets with the same language version require only one verified catalog request`() {
        val accesses = listOf(access, access.copy(snippetId = UUID.randomUUID()))
        val metadata =
            MetadataLanguages { ids, _ ->
                assertThat(ids).containsExactlyInAnyOrderElementsOf(accesses.map { it.snippetId })
                setOf(scope)
            }
        `when`(language.lintingCatalog("printscript", "1.1"))
            .thenReturn(Attributed(LintCatalogResult.Catalog(emptyList()), ProcessorIdentity.Verified(processor)))
        val result = VerifyLintRead(metadata, language).prepare(accesses, filter) as PreparedLintRead.Ready
        assertThat(result.basis.processors).isEqualTo(mapOf(scope to processor))
        assertThat(result.basis.owners.values).containsOnly("owner")
        verify(language, times(1)).lintingCatalog("printscript", "1.1")
    }

    @Test
    fun `empty authorized metadata does not call a remote processor`() {
        val result = VerifyLintRead(MetadataLanguages { _, _ -> emptySet() }, language).prepare(emptyList(), filter)
        assertThat(result).isEqualTo(PreparedLintRead.Ready(LintReadBasis(emptyMap(), emptyMap())))
        verifyNoInteractions(language)
    }

    @Test
    fun `a missing or malformed revision cannot certify a catalog or turn it into an empty listing`() {
        listOf(ProcessorIdentity.Missing, ProcessorIdentity.Malformed("1.1")).forEach { identity ->
            `when`(language.lintingCatalog("printscript", "1.1"))
                .thenReturn(Attributed(LintCatalogResult.Catalog(emptyList()), identity))
            assertThat(
                VerifyLintRead(
                    MetadataLanguages {
                        _,
                        _,
                        ->
                        setOf(scope)
                    },
                    language,
                ).prepare(listOf(access), filter),
            ).isEqualTo(PreparedLintRead.Unavailable)
        }
    }

    @Test
    fun `provider failure remains unavailable even with a valid processor header`() {
        `when`(language.lintingCatalog("printscript", "1.1"))
            .thenReturn(Attributed(LintCatalogResult.Failed("down"), ProcessorIdentity.Verified(processor)))
        assertThat(VerifyLintRead(MetadataLanguages { _, _ -> setOf(scope) }, language).prepare(listOf(access), filter))
            .isEqualTo(PreparedLintRead.Unavailable)
    }
}
