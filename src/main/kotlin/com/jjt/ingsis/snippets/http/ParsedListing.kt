package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.listing.ListingQuery
import com.jjt.ingsis.snippets.metadata.PageRequest
import com.jjt.ingsis.snippets.metadata.SnippetFilter
import com.jjt.ingsis.snippets.metadata.SnippetOrder
import com.jjt.ingsis.snippets.metadata.SortDirection
import com.jjt.ingsis.snippets.metadata.SortField
import com.jjt.ingsis.snippets.permissions.RelationFilter

private const val RELATION = "relation"
private const val NAME = "name"
private const val LANGUAGE = "language"
private const val SORT = "sort"
private const val DIRECTION = "direction"
private const val PAGE = "page"
private const val PAGE_SIZE = "page_size"
private const val DEFAULT_PAGE_SIZE = 10
private const val MAX_PAGE_SIZE = 100

private val known = setOf(RELATION, NAME, LANGUAGE, SORT, DIRECTION, PAGE, PAGE_SIZE)
private val digits = Regex("\\d{1,9}")

sealed interface ParsedListing {
    data class Valid(
        val query: ListingQuery,
    ) : ParsedListing

    data class Invalid(
        val parameter: String,
    ) : ParsedListing
}

fun listingQueryOf(parameters: Map<String, List<String>>): ParsedListing {
    val malformed =
        parameters.keys.firstOrNull { it !in known } ?: parameters.entries.firstOrNull { it.value.size != 1 }?.key
    if (malformed != null) return ParsedListing.Invalid(malformed)

    val values = parameters.mapValues { it.value.single() }
    val relation = choice(values[RELATION], RelationFilter.entries, RelationFilter.ALL)
    val field = choice(values[SORT], SortField.entries, SortField.NAME)
    val direction = choice(values[DIRECTION], SortDirection.entries, SortDirection.ASC)
    val page = number(values[PAGE], 0, 0..Int.MAX_VALUE)
    val size = number(values[PAGE_SIZE], DEFAULT_PAGE_SIZE, 1..MAX_PAGE_SIZE)

    return when {
        relation == null -> {
            ParsedListing.Invalid(RELATION)
        }

        field == null -> {
            ParsedListing.Invalid(SORT)
        }

        direction == null -> {
            ParsedListing.Invalid(DIRECTION)
        }

        page == null -> {
            ParsedListing.Invalid(PAGE)
        }

        size == null -> {
            ParsedListing.Invalid(PAGE_SIZE)
        }

        else -> {
            val filter = SnippetFilter(name = values[NAME].orEmpty(), language = values[LANGUAGE].orEmpty())
            ParsedListing.Valid(
                ListingQuery(
                    relation = relation,
                    filter = filter,
                    order = SnippetOrder(field, direction),
                    page = PageRequest(page, size),
                ),
            )
        }
    }
}

private fun <E : Enum<E>> choice(
    value: String?,
    options: List<E>,
    default: E,
): E? = if (value == null) default else options.find { it.name.lowercase() == value }

private fun number(
    value: String?,
    default: Int,
    range: IntRange,
): Int? = if (value == null) default else value.takeIf { digits.matches(it) }?.toInt()?.takeIf { it in range }
