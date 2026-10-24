package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.metadata.PageRequest
import com.jjt.ingsis.snippets.users.UserQuery

private const val NAME = "name"

private val known = setOf(NAME, PAGE, PAGE_SIZE)

sealed interface ParsedUserSearch {
    data class Valid(
        val query: UserQuery,
    ) : ParsedUserSearch

    data class Invalid(
        val parameter: String,
    ) : ParsedUserSearch
}

fun userQueryOf(parameters: Map<String, List<String>>): ParsedUserSearch {
    val malformed = malformedParameter(parameters, known)
    if (malformed != null) return ParsedUserSearch.Invalid(malformed)

    val values = parameters.mapValues { it.value.single() }
    val page = number(values[PAGE], 0, 0..Int.MAX_VALUE)
    val size = number(values[PAGE_SIZE], DEFAULT_PAGE_SIZE, 1..MAX_PAGE_SIZE)

    return when {
        page == null -> ParsedUserSearch.Invalid(PAGE)
        size == null -> ParsedUserSearch.Invalid(PAGE_SIZE)
        else -> ParsedUserSearch.Valid(UserQuery(values[NAME].orEmpty(), PageRequest(page, size)))
    }
}
