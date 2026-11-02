package com.jjt.ingsis.snippets.lintquery

import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.linting.LintCatalogResult
import com.jjt.ingsis.snippets.metadata.SnippetFilter
import com.jjt.ingsis.snippets.permissions.SnippetAccess
import org.springframework.stereotype.Component

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
                if (catalog.result is LintCatalogResult.Catalog && processor != null) scope to processor else null
            }
        return if (revisions.any { it == null }) {
            PreparedLintRead.Unavailable
        } else {
            PreparedLintRead.Ready(LintReadBasis(revisions.filterNotNull().toMap(), owners))
        }
    }
}
