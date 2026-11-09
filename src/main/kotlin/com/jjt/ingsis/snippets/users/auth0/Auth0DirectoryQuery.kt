package com.jjt.ingsis.snippets.users.auth0

import com.jjt.ingsis.snippets.metadata.PageRequest

internal const val SEARCH_LIMIT = 1000L
private const val LUCENE_RESERVED = "+-!(){}[]^\"~*?:\\/&|"

// Literal prefix search: client input cannot introduce Lucene fields, wildcards or boolean clauses.
internal fun directoryQuery(
    name: String,
    excluding: String,
    page: PageRequest,
): ManagementRequest {
    val literal =
        name
            .trim()
            .map { char ->
                if (char.isWhitespace() || char in LUCENE_RESERVED) "\\$char" else char.toString()
            }.joinToString("")
    val actor = excluding.replace("\\", "\\\\").replace("\"", "\\\"")
    val exclude = "NOT user_id:\"$actor\""
    val query = if (literal.isEmpty()) exclude else "name:$literal* AND $exclude"
    return ManagementRequest(
        path = "/api/v2/users",
        query =
            mapOf(
                "q" to query,
                "page" to page.number,
                "per_page" to page.size,
                "include_totals" to true,
                "fields" to "user_id,name",
                "include_fields" to true,
                "search_engine" to "v3",
                "primary_order" to true,
                "sort" to "name:1",
            ),
    )
}
