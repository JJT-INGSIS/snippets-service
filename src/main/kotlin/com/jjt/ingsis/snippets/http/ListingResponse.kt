package com.jjt.ingsis.snippets.http

import com.fasterxml.jackson.annotation.JsonProperty
import com.jjt.ingsis.snippets.listing.ListedSnippet
import com.jjt.ingsis.snippets.listing.SnippetPage
import java.util.UUID

private val notImplemented = LintResponse("not-implemented")

data class LintResponse(
    val status: String,
)

data class ListedSnippetResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val language: String,
    val version: String,
    val extension: String?,
    val ownerId: String,
    val relation: String,
) {
    val lint: LintResponse = notImplemented

    companion object {
        fun from(snippet: ListedSnippet): ListedSnippetResponse {
            val metadata = snippet.metadata
            val access = snippet.access

            return ListedSnippetResponse(
                id = metadata.id,
                name = metadata.name,
                description = metadata.description,
                language = metadata.language,
                version = metadata.version,
                extension = snippet.extension,
                ownerId = access.ownerId,
                relation = access.relation.name.lowercase(),
            )
        }
    }
}

data class SnippetPageResponse(
    val page: Int,
    @get:JsonProperty("page_size") val pageSize: Int,
    val count: Long,
    val snippets: List<ListedSnippetResponse>,
) {
    companion object {
        fun from(page: SnippetPage): SnippetPageResponse =
            SnippetPageResponse(
                page = page.page.number,
                pageSize = page.page.size,
                count = page.count,
                snippets = page.snippets.map(ListedSnippetResponse::from),
            )
    }
}
