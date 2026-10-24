package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.metadata.PageRequest
import com.jjt.ingsis.snippets.users.UserQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private fun parse(vararg parameters: Pair<String, String>): ParsedUserSearch =
    userQueryOf(parameters.groupBy({ it.first }, { it.second }))

class ParsedUserSearchTest {
    @Test
    fun `defaults search everyone on the first page of ten`() {
        assertThat(parse()).isEqualTo(ParsedUserSearch.Valid(UserQuery("", PageRequest(0, 10))))
    }

    @Test
    fun `name and page are read without trimming the name`() {
        assertThat(parse("name" to " An ", "page" to "2", "page_size" to "5"))
            .isEqualTo(ParsedUserSearch.Valid(UserQuery(" An ", PageRequest(2, 5))))
    }

    @Test
    fun `invalid pages name the parameter that caused them`() {
        listOf("-1", "+1", "1.5", "", "abc", "9999999999").forEach { value ->
            assertThat(parse("page" to value)).isEqualTo(ParsedUserSearch.Invalid("page"))
        }
        listOf("0", "101", "", "five").forEach { value ->
            assertThat(parse("page_size" to value)).isEqualTo(ParsedUserSearch.Invalid("page_size"))
        }
    }

    @Test
    fun `unknown and repeated parameters are rejected instead of ignored`() {
        assertThat(parse("relation" to "all")).isEqualTo(ParsedUserSearch.Invalid("relation"))
        assertThat(parse("pageSize" to "5")).isEqualTo(ParsedUserSearch.Invalid("pageSize"))
        assertThat(parse("name" to "a", "name" to "b")).isEqualTo(ParsedUserSearch.Invalid("name"))
    }
}
