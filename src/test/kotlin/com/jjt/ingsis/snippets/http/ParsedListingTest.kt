package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.listing.ListingQuery
import com.jjt.ingsis.snippets.metadata.PageRequest
import com.jjt.ingsis.snippets.metadata.SnippetFilter
import com.jjt.ingsis.snippets.metadata.SnippetOrder
import com.jjt.ingsis.snippets.metadata.SortDirection
import com.jjt.ingsis.snippets.metadata.SortField
import com.jjt.ingsis.snippets.permissions.RelationFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private fun parse(vararg parameters: Pair<String, String>): ParsedListing =
    listingQueryOf(parameters.groupBy({ it.first }, { it.second }))

class ParsedListingTest {
    @Test
    fun `defaults list everything by name ascending on the first page of ten`() {
        assertThat(parse()).isEqualTo(
            ParsedListing.Valid(
                ListingQuery(
                    relation = RelationFilter.ALL,
                    filter = SnippetFilter(name = "", language = ""),
                    order = SnippetOrder(SortField.NAME, SortDirection.ASC),
                    page = PageRequest(0, 10),
                ),
            ),
        )
    }

    @Test
    fun `every documented parameter is read`() {
        val parsed =
            parse(
                "relation" to "shared",
                "name" to " He ",
                "language" to "printscript",
                "sort" to "language",
                "direction" to "desc",
                "page" to "3",
                "page_size" to "100",
            )

        assertThat(parsed).isEqualTo(
            ParsedListing.Valid(
                ListingQuery(
                    relation = RelationFilter.SHARED,
                    filter = SnippetFilter(name = " He ", language = "printscript"),
                    order = SnippetOrder(SortField.LANGUAGE, SortDirection.DESC),
                    page = PageRequest(3, 100),
                ),
            ),
        )
    }

    @Test
    fun `invalid values name the parameter that caused them`() {
        mapOf(
            "relation" to listOf("ALL", "mine", ""),
            "sort" to listOf("lint", "NAME", ""),
            "direction" to listOf("up", "ASC"),
            "page" to listOf("-1", "+1", "1.5", "", "abc", "9999999999"),
            "page_size" to listOf("0", "101", "500", "", "ten"),
        ).forEach { (parameter, values) ->
            values.forEach { value ->
                assertThat(parse(parameter to value)).isEqualTo(ParsedListing.Invalid(parameter))
            }
        }
    }

    @Test
    fun `unknown and repeated parameters are rejected instead of ignored`() {
        assertThat(parse("lint" to "compliant")).isEqualTo(ParsedListing.Invalid("lint"))
        assertThat(parse("compliance" to "")).isEqualTo(ParsedListing.Invalid("compliance"))
        assertThat(parse("pageSize" to "5")).isEqualTo(ParsedListing.Invalid("pageSize"))
        assertThat(parse("page" to "0", "page" to "1")).isEqualTo(ParsedListing.Invalid("page"))
    }

    @Test
    fun `empty name and language mean no filter`() {
        val parsed = parse("name" to "", "language" to "")

        assertThat((parsed as ParsedListing.Valid).query.filter).isEqualTo(SnippetFilter(name = "", language = ""))
    }
}
