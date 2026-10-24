package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(MetadataTestConfiguration::class)
class UsersHttpTest
    @Autowired
    constructor(
        @Value("\${local.server.port}") port: Int,
    ) {
        private val api = SnippetsApi(port)

        @Test
        fun `the documented recipient searches are the real responses`() {
            listOf("Usuarios que contienen «an»", "Sin coincidencias")
                .map(sharingContract::example)
                .forEach { example ->
                    val lines = example.request.lines()
                    val path = lines.first().removePrefix("GET ")
                    val actor = lines.drop(1).single().removePrefix("X-Dev-Actor-Id: ")

                    val answer = api.users(path.removePrefix("/users"), listOf(actor))

                    assertThat(answer.status).describedAs(example.title).isEqualTo(example.status)
                    assertThat(answer.body).describedAs(example.title).isEqualTo(json.readTree(example.response))
                }
        }

        @Test
        fun `the configured development catalogue is exposed with only id and name`() {
            val answer = api.users("", listOf("dev-outsider"))

            assertThat(answer.status).isEqualTo(200)
            assertThat(answer.body.get("count").asInt()).isEqualTo(5)
            val users = answer.body.get("users").values()
            assertThat(users.map { it.get("id").asString() })
                .containsExactly("dev-ana", "dev-bruno", "dev-juan", "dev-juanfran", "dev-thiago")
            assertThat(users.map { it.propertyNames().toSet() }).containsOnly(setOf("id", "name"))
        }

        @Test
        fun `a page after the last one is empty and keeps the count`() {
            val answer = api.users("?page=3&page_size=2", listOf("dev-juan"))

            assertThat(answer.status).isEqualTo(200)
            assertThat(answer.body.get("count").asInt()).isEqualTo(4)
            assertThat(answer.body.get("users").isEmpty).isTrue()
        }

        @Test
        fun `invalid parameters and missing identity are explicit errors`() {
            val invalid = api.users("?page_size=500")
            assertThat(invalid.status).isEqualTo(400)
            assertThat(invalid.type).isEqualTo("urn:snippets:problem:invalid-request")
            assertThat(invalid.body.get("parameter").asString()).isEqualTo("page_size")

            val anonymous = api.users("", actors = emptyList())
            assertThat(anonymous.status).isEqualTo(401)
            assertThat(anonymous.type).isEqualTo("urn:snippets:problem:identity-unavailable")
            assertThat(api.users("", actors = listOf(" ")).status).isEqualTo(401)
        }
    }
