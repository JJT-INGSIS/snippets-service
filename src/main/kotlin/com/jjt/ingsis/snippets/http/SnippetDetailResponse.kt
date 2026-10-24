package com.jjt.ingsis.snippets.http

import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.jjt.ingsis.snippets.detail.SnippetDetail

data class CapabilitiesResponse(
    val modify: Boolean,
    val share: Boolean,
)

data class SnippetDetailResponse(
    @get:JsonUnwrapped val snippet: ListedSnippetResponse,
    val code: String,
    val capabilities: CapabilitiesResponse,
) {
    companion object {
        fun from(detail: SnippetDetail): SnippetDetailResponse =
            SnippetDetailResponse(
                snippet = ListedSnippetResponse.from(detail.snippet),
                code = detail.code,
                capabilities = CapabilitiesResponse(detail.capabilities.modify, detail.capabilities.share),
            )
    }
}
