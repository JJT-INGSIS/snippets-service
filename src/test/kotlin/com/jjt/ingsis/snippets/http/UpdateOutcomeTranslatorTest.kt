package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.creation.CreationResponse
import com.jjt.ingsis.snippets.creation.PreparationRejection
import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.update.UpdateFailure
import com.jjt.ingsis.snippets.update.UpdateOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.ProblemDetail
import java.util.UUID

class UpdateOutcomeTranslatorTest {
    private val translator = UpdateOutcomeTranslator(RejectionTranslator())

    private val confirmed =
        SnippetMetadata(
            id = UUID.randomUUID(),
            name = "Renamed",
            description = null,
            language = "printscript",
            version = "1.1",
            state = SnippetState.CONFIRMED,
        )

    @Test
    fun `an updated snippet is 200 with its new data`() {
        val response = translator.toResponse(UpdateOutcome.Updated(confirmed))

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body).isEqualTo(CreationResponse.fromConfirmed(confirmed))
    }

    @Test
    fun `a pending snippet is never answered as updated`() {
        val pending = confirmed.copy(state = SnippetState.PENDING)

        assertThat(problemOf(UpdateOutcome.Updated(pending))).isEqualTo(SnippetProblem.METADATA_UNAVAILABLE)
    }

    @Test
    fun `missing, pending, denied and unowned snippets are told apart`() {
        assertThat(problemOf(UpdateOutcome.Missing)).isEqualTo(SnippetProblem.SNIPPET_NOT_FOUND)
        assertThat(problemOf(UpdateOutcome.Pending)).isEqualTo(SnippetProblem.SNIPPET_PENDING)
        assertThat(problemOf(UpdateOutcome.Denied)).isEqualTo(SnippetProblem.MODIFICATION_DENIED)
        assertThat(problemOf(UpdateOutcome.OwnershipMissing)).isEqualTo(SnippetProblem.OWNERSHIP_MISSING)
    }

    @Test
    fun `a Permissions failure is a dependency failure and never a denial`() {
        val failure = UpdateOutcome.Rejected(PreparationRejection.TechnicalFailure("permissions"))

        assertThat(problemOf(failure)).isEqualTo(SnippetProblem.DEPENDENCY_FAILURE)
    }

    @Test
    fun `rejections keep their diagnostics and failures are never a success`() {
        val diagnostic = Diagnostic(rule = "UNEXPECTED_TOKEN", message = "Any wording", line = 2, column = 1)
        val rejected = UpdateOutcome.Rejected(PreparationRejection.InvalidCode(listOf(diagnostic)))
        val invalidCode = translator.toResponse(rejected)

        assertThat((invalidCode.body as ProblemDetail).properties).containsEntry("diagnostics", listOf(diagnostic))
        assertThat(problemOf(UpdateOutcome.Failed(UpdateFailure.STORAGE_UNAVAILABLE)))
            .isEqualTo(SnippetProblem.STORAGE_UNAVAILABLE)
        assertThat(problemOf(UpdateOutcome.Failed(UpdateFailure.METADATA_UNAVAILABLE)))
            .isEqualTo(SnippetProblem.METADATA_UNAVAILABLE)
    }

    private fun problemOf(outcome: UpdateOutcome): SnippetProblem {
        val response = translator.toResponse(outcome)
        val body = response.body as ProblemDetail

        assertThat(response.statusCode.value()).isEqualTo(body.status)

        return SnippetProblem.entries.single { problem -> problem.body().type == body.type }
    }
}
