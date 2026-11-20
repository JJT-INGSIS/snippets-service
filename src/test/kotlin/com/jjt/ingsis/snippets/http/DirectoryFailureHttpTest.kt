package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.metadata.PageRequest
import com.jjt.ingsis.snippets.users.DirectoryMembership
import com.jjt.ingsis.snippets.users.DirectorySearch
import com.jjt.ingsis.snippets.users.DirectoryUser
import com.jjt.ingsis.snippets.users.UserDirectory
import com.jjt.ingsis.snippets.users.UserPage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(MetadataTestConfiguration::class)
@MockitoBean(types = [UserDirectory::class])
class DirectoryFailureHttpTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") port: Int,
        private val directory: UserDirectory,
    ) {
        private val api = SnippetsApi(port)

        @Test fun `provider failure is a typed gateway error without a fictional empty page`() {
            `when`(directory.search("", "dev-juan", PageRequest(0, 10))).thenReturn(DirectorySearch.Unavailable)
            val answer = api.users("")
            assertThat(answer.status).isEqualTo(502)
            assertThat(answer.type).isEqualTo("urn:snippets:problem:directory-unavailable")
            assertThat(answer.body.has("users")).isFalse()
            assertThat(answer.body.has("count")).isFalse()
        }

        @Test fun `broad searches ask for a narrower name without returning a capped total`() {
            `when`(directory.search("", "dev-juan", PageRequest(0, 10))).thenReturn(DirectorySearch.RefineSearch)
            val answer = api.users("")
            assertThat(answer.status).isEqualTo(422)
            assertThat(answer.type).isEqualTo("urn:snippets:problem:directory-search-too-broad")
            assertThat(answer.body.get("parameter").asString()).isEqualTo("name")
            assertThat(answer.body.has("count")).isFalse()
        }

        @Test fun `native directory values keep the existing minimal public DTO`() {
            `when`(directory.search("", "dev-juan", PageRequest(0, 10))).thenReturn(
                DirectorySearch.Found(UserPage(1, listOf(DirectoryUser("auth0|bob", "Bob")))),
            )
            val answer = api.users("")
            assertThat(answer.status).isEqualTo(200)
            assertThat(
                answer.body
                    .get("users")
                    .get(0)
                    .propertyNames()
                    .toSet(),
            ).isEqualTo(setOf("id", "name"))
            assertThat(
                answer.body
                    .get("users")
                    .get(0)
                    .get("id")
                    .asString(),
            ).isEqualTo("auth0|bob")
        }

        @Test fun `sharing distinguishes a directory outage from an unknown recipient`() {
            val id = UUID.randomUUID().toString()
            `when`(directory.contains("dev-ana")).thenReturn(DirectoryMembership.UNAVAILABLE)
            val failure = api.share(id, "dev-ana")
            assertThat(failure.status).isEqualTo(502)
            assertThat(failure.type).isEqualTo("urn:snippets:problem:directory-unavailable")
            `when`(directory.contains("dev-ana")).thenReturn(DirectoryMembership.ABSENT)
            assertThat(api.share(id, "dev-ana").type).isEqualTo("urn:snippets:problem:invalid-recipient")
        }
    }
