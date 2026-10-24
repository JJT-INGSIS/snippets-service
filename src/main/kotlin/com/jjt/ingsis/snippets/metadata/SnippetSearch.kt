package com.jjt.ingsis.snippets.metadata

import java.util.UUID

enum class SortField {
    NAME,
    LANGUAGE,
}

enum class SortDirection {
    ASC,
    DESC,
}

data class SnippetFilter(
    val name: String,
    val language: String,
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
)

fun interface SnippetSearch {
    fun search(
        ids: Set<UUID>,
        filter: SnippetFilter,
        order: SnippetOrder,
        page: PageRequest,
    ): MetadataPage
}
