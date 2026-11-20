package com.jjt.ingsis.snippets.http

import com.fasterxml.jackson.annotation.JsonProperty
import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.lintquery.LintFinding
import com.jjt.ingsis.snippets.listing.ListedSnippet
import com.jjt.ingsis.snippets.listing.SnippetPage
import java.util.UUID

data class LintResponse(
    val status: String,
    val reason: String?,
    val diagnostics: List<Diagnostic>,
    val codeHash: String?,
    val rulesRevision: String?,
    val processorRevision: String?,
) {
    companion object {
        fun from(finding: LintFinding) =
            LintResponse(
                status = finding.status.value,
                reason = finding.reason,
                diagnostics = finding.diagnostics,
                codeHash = finding.codeHash,
                rulesRevision = finding.rulesRevision,
                processorRevision = finding.processorRevision,
            )
    }
}

data class ListedSnippetResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val language: String,
    val version: String,
    val extension: String?,
    val ownerId: String,
    val relation: String,
    val lint: LintResponse,
) {
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
                lint = LintResponse.from(snippet.lint),
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
