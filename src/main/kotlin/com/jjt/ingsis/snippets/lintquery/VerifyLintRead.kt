package com.jjt.ingsis.snippets.lintquery

import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.language.linting.LintCatalogResult
import com.jjt.ingsis.snippets.lintingrules.LintingSelection
import com.jjt.ingsis.snippets.metadata.SnippetFilter
import com.jjt.ingsis.snippets.permissions.SnippetAccess
import org.springframework.stereotype.Component

private data class VerifiedCatalog(
    val scope: LanguageVersion,
    val processor: ProcessorRevision,
    val defaults: String?,
)

@Component
class VerifyLintRead(
    private val metadata: MetadataLanguages,
    private val language: Language,
) : LintReadPreparation {
    override fun prepare(
        accesses: List<SnippetAccess>,
        filter: SnippetFilter,
    ): PreparedLintRead {
        val owners = accesses.associate { it.snippetId to it.ownerId }
        val scopes = metadata.find(owners.keys, filter)
        val revisions =
            scopes.map { scope ->
                val catalog = language.lintingCatalog(scope.language, scope.version)
                val processor = catalog.processor.verifiedOrNull()
                val rules = catalog.result as? LintCatalogResult.Catalog
                if (rules != null && processor != null) {
                    VerifiedCatalog(scope, processor, rules.defaults?.let { LintingSelection(it).revision })
                } else {
                    null
                }
            }
        return if (revisions.any { it == null }) {
            PreparedLintRead.Unavailable
        } else {
            val verified = revisions.filterNotNull()
            PreparedLintRead.Ready(
                LintReadBasis(
                    processors = verified.associate { it.scope to it.processor },
                    owners = owners,
                    defaults = verified.mapNotNull { catalog -> catalog.defaults?.let { catalog.scope to it } }.toMap(),
                ),
            )
        }
    }
}
