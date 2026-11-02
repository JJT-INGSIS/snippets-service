package com.jjt.ingsis.snippets.lintquery

import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.SnippetFilter
import com.jjt.ingsis.snippets.permissions.SnippetAccess
import org.springframework.stereotype.Component

sealed interface DetailLint {
    data class Found(
        val finding: LintFinding,
    ) : DetailLint

    data object Unavailable : DetailLint
}

fun interface LintDetailResults {
    fun find(
        linked: LinkedSnippet,
        basis: LintReadBasis,
    ): DetailLint
}

fun interface DetailLintReading {
    fun read(
        linked: LinkedSnippet,
        access: SnippetAccess,
    ): DetailLint
}

@Component
class ReadDetailLint(
    private val preparation: LintReadPreparation,
    private val results: LintDetailResults,
) : DetailLintReading {
    override fun read(
        linked: LinkedSnippet,
        access: SnippetAccess,
    ): DetailLint =
        when (val prepared = preparation.prepare(listOf(access), SnippetFilter("", linked.metadata.language))) {
            is PreparedLintRead.Ready -> results.find(linked, prepared.basis)
            PreparedLintRead.Unavailable -> DetailLint.Unavailable
        }
}
