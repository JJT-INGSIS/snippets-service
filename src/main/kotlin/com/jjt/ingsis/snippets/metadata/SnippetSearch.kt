package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.lintquery.LintFinding
import com.jjt.ingsis.snippets.lintquery.LintReadBasis
import com.jjt.ingsis.snippets.lintquery.LintStatus
import java.util.UUID

enum class SortField {
    NAME,
    LANGUAGE,
    LINT,
}

enum class SortDirection {
    ASC,
    DESC,
}

data class SnippetFilter(
    val name: String,
    val language: String,
    val lintStatus: LintStatus? = null,
    val lintBasis: LintReadBasis? = null,
)

data class SnippetOrder(
    val field: SortField,
    val direction: SortDirection,
)

data class PageRequest(
    val number: Int,
    val size: Int,
) {
    val offset: Long
        get() = number.toLong() * size
}

data class MetadataPage(
    val count: Long,
    val snippets: List<SnippetMetadata>,
    val lint: Map<UUID, LintFinding> = emptyMap(),
    val lintVerified: Boolean = true,
)

fun interface SnippetSearch {
    fun search(
        ids: Set<UUID>,
        filter: SnippetFilter,
        order: SnippetOrder,
        page: PageRequest,
    ): MetadataPage
}
