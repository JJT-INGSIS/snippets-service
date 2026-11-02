package com.jjt.ingsis.snippets.lintquery

import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.metadata.SnippetFilter
import com.jjt.ingsis.snippets.permissions.SnippetAccess
import java.util.UUID

enum class LintStatus(
    val value: String,
) {
    COMPLIANT("compliant"),
    NON_COMPLIANT("not-compliant"),
    PENDING("pending"),
    FAILED("failed"),
}

data class LintFinding(
    val status: LintStatus,
    val reason: String? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
    val codeHash: String? = null,
    val rulesRevision: String? = null,
    val processorRevision: String? = null,
) {
    companion object {
        val NOT_EVALUATED = LintFinding(LintStatus.PENDING, "NOT_EVALUATED")
        val SUPERSEDED = LintFinding(LintStatus.PENDING, "SUPERSEDED")
    }
}

data class LanguageVersion(
    val language: String,
    val version: String,
)

data class LintReadBasis(
    val processors: Map<LanguageVersion, ProcessorRevision>,
    val owners: Map<UUID, String>,
)

sealed interface PreparedLintRead {
    data class Ready(
        val basis: LintReadBasis,
    ) : PreparedLintRead

    data object Unavailable : PreparedLintRead
}

fun interface LintReadPreparation {
    fun prepare(
        accesses: List<SnippetAccess>,
        filter: SnippetFilter,
    ): PreparedLintRead
}

fun interface MetadataLanguages {
    fun find(
        ids: Set<UUID>,
        filter: SnippetFilter,
    ): Set<LanguageVersion>
}
