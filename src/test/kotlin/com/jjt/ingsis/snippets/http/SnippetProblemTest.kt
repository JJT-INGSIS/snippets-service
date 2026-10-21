package com.jjt.ingsis.snippets.http

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

class SnippetProblemTest {
    @Test
    fun `the documented error tables list exactly the problems the service answers`() {
        val documented = creationContract.problems + updateContract.problems
        val answered =
            SnippetProblem.entries.map { problem ->
                DocumentedProblem(problem.status.value(), "urn:snippets:problem:${problem.category}")
            }

        assertThat(documented.distinct()).containsExactlyInAnyOrderElementsOf(answered)
    }

    @Test
    fun `every problem answers its own status and type`() {
        SnippetProblem.entries.forEach { problem ->
            val response = problem.response()
            val body = checkNotNull(response.body)

            assertThat(response.statusCode.value()).isEqualTo(problem.status.value())
            assertThat(body.status).isEqualTo(problem.status.value())
            assertThat(body.type).isEqualTo(URI.create("urn:snippets:problem:${problem.category}"))
        }
    }
}
