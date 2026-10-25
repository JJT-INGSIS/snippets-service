package com.jjt.ingsis.snippets.http

internal const val PAGE = "page"
internal const val PAGE_SIZE = "page_size"
internal const val DEFAULT_PAGE_SIZE = 10
internal const val MAX_PAGE_SIZE = 100

private val digits = Regex("\\d{1,9}")

internal fun malformedParameter(
    parameters: Map<String, List<String>>,
    known: Set<String>,
): String? = parameters.keys.firstOrNull { it !in known } ?: parameters.entries.firstOrNull { it.value.size != 1 }?.key

internal fun <E : Enum<E>> choice(
    value: String?,
    options: List<E>,
    default: E,
): E? = if (value == null) default else options.find { it.name.lowercase() == value }

internal fun number(
    value: String?,
    default: Int,
    range: IntRange,
): Int? = if (value == null) default else value.takeIf { digits.matches(it) }?.toInt()?.takeIf { it in range }
