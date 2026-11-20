package com.jjt.ingsis.snippets.users.auth0

import com.jjt.ingsis.snippets.metadata.PageRequest
import com.jjt.ingsis.snippets.users.DirectoryMembership
import com.jjt.ingsis.snippets.users.DirectorySearch
import com.jjt.ingsis.snippets.users.DirectoryUser
import com.jjt.ingsis.snippets.users.UserPage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class Auth0UserDirectoryTest {
    private val fixture = ManagementFixture()
    private val directory = fixture.directory()

    @AfterEach fun close() {
        fixture.close()
    }

    @Test fun `native search fetches exactly one minimal page excluding the authenticated subject`() {
        fixture.usersResponse =
            DirectoryResponse(
                200,
                """{"start":2,"total":3,
                "users":[{"user_id":"auth0|bob","name":"Bob","email":"never-public@example.invalid"}]}""",
            )
        val result = directory.search("Bo", "auth0|alice", PageRequest(1, 2))
        assertThat(result).isEqualTo(DirectorySearch.Found(UserPage(3, listOf(DirectoryUser("auth0|bob", "Bob")))))
        val token = fixture.requests[0]
        val body = fixture.json.readTree(token.body)
        assertThat(token.path).isEqualTo("/oauth/token")
        assertThat(token.authorization).isNull()
        assertThat(body.get("grant_type").asString()).isEqualTo("client_credentials")
        assertThat(body.get("client_id").asString()).isEqualTo("directory-client")
        assertThat(body.get("scope").asString()).isEqualTo("read:users")
        assertThat(body.get("audience").asString()).isEqualTo("https://tenant.fixture/api/v2/")
        val request = fixture.requests[1]
        assertThat(request.authorization).isEqualTo("Bearer management-token")
        assertThat(request.query)
            .containsEntry("q", "name:Bo* AND NOT user_id:\"auth0|alice\"")
            .containsEntry("fields", "user_id,name")
            .containsEntry("page", "1")
            .containsEntry("per_page", "2")
            .containsEntry("sort", "name:1")
            .containsEntry("primary_order", "true")
        assertThat(fixture.requests).hasSize(2)
    }

    @Test fun `user text cannot inject a wildcard field or Lucene boolean clause`() {
        directory.search("a\" OR *:*", "auth0|a\"\\b", PageRequest(0, 10))
        val query =
            fixture.requests
                .last()
                .query
                .getValue("q")
        assertThat(query).isEqualTo("name:a\\\"\\ OR\\ \\*\\:\\** AND NOT user_id:\"auth0|a\\\"\\\\b\"")
    }

    @Test fun `membership uses a URL encoded direct ID lookup and does not rely on search indexing`() {
        fixture.memberResponse = DirectoryResponse(200, """{"user_id":"auth0|bob/a?b#c"}""")
        assertThat(directory.contains("auth0|bob/a?b#c")).isEqualTo(DirectoryMembership.PRESENT)
        val request = fixture.requests.last()
        assertThat(request.path).isEqualTo("/api/v2/users/auth0|bob/a?b#c")
        assertThat(request.query).containsOnlyKeys("fields", "include_fields").containsEntry("fields", "user_id")
        assertThat(fixture.requests.none { it.path == "/api/v2/users" }).isTrue()
    }

    @Test fun `only a direct not found response means an absent recipient`() {
        fixture.memberResponse = DirectoryResponse(404, """{"statusCode":404}""")
        assertThat(directory.contains("auth0|bob")).isEqualTo(DirectoryMembership.ABSENT)
        listOf(400, 401, 403, 429, 500, 503).forEach { status ->
            fixture.memberResponse = DirectoryResponse(status, """{"message":"provider problem"}""")
            assertThat(
                directory.contains("auth0|bob"),
            ).describedAs("HTTP $status").isEqualTo(DirectoryMembership.UNAVAILABLE)
        }
    }

    @Test fun `malformed and mismatching profiles never become known recipients`() {
        listOf("{}", "[]", "not json", """{"user_id":"someone-else"}""", """{"user_id":12}""").forEach { body ->
            fixture.memberResponse = DirectoryResponse(200, body)
            assertThat(directory.contains("auth0|bob")).isEqualTo(DirectoryMembership.UNAVAILABLE)
        }
        fixture.memberResponse = DirectoryResponse(200, """{"user_id":"auth0|bob"}""", "text/html")
        assertThat(directory.contains("auth0|bob")).isEqualTo(DirectoryMembership.UNAVAILABLE)
    }

    @Test fun `broad counts and pages outside the provider limit require refining instead of truncating`() {
        fixture.usersResponse = DirectoryResponse(200, """{"start":0,"total":1000,"users":[]}""")
        assertThat(directory.search("", "auth0|alice", PageRequest(0, 10))).isEqualTo(DirectorySearch.RefineSearch)
        val before = fixture.requests.size
        assertThat(
            directory.search("", "auth0|alice", PageRequest(Int.MAX_VALUE, 100)),
        ).isEqualTo(DirectorySearch.RefineSearch)
        assertThat(fixture.requests).hasSize(before)
    }

    @Test fun `malformed pages and actor leakage fail explicitly without manufacturing a count`() {
        listOf(
            "[]",
            "{}",
            "not json",
            """{"start":0,"total":1,"users":[]}""",
            """{"start":99,"total":1,"users":[{"user_id":"auth0|bob","name":"Bob"}]}""",
            """{"start":0,"total":1,"users":[{"user_id":"auth0|alice","name":"Alice"}]}""",
            """{"start":0,"total":1,"users":[{"user_id":"auth0|bob"}]}""",
        ).forEach { body ->
            fixture.usersResponse = DirectoryResponse(200, body)
            assertThat(
                directory.search("", "auth0|alice", PageRequest(0, 10)),
            ).describedAs(body).isEqualTo(DirectorySearch.Unavailable)
        }
    }

    @Test fun `remote outages never become a successful empty page`() {
        listOf(401, 403, 429, 500, 503).forEach { code ->
            fixture.usersResponse = DirectoryResponse(code, "{}")
            assertThat(directory.search("", "auth0|alice", PageRequest(0, 10))).isEqualTo(DirectorySearch.Unavailable)
        }
        fixture.usersResponse = DirectoryResponse(200, """{"start":0,"total":0,"users":[]}""")
        assertThat(
            directory.search("", "auth0|alice", PageRequest(0, 10)),
        ).isEqualTo(DirectorySearch.Found(UserPage(0, emptyList())))
    }

    @Test
    fun `Management redirects are not followed with a service credential`() {
        fixture.memberResponse = DirectoryResponse(302, "{}", location = fixture.baseUrl + "/stolen")
        assertThat(directory.contains("auth0|bob")).isEqualTo(DirectoryMembership.UNAVAILABLE)
        assertThat(fixture.requests.map { it.path }).doesNotContain("/stolen")
    }
}
