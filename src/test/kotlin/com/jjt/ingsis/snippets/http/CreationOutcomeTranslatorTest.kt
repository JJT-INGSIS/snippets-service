package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.creation.CreationFailure
import com.jjt.ingsis.snippets.creation.CreationOutcome
import com.jjt.ingsis.snippets.creation.PreparationRejection
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import java.util.UUID

class CreationOutcomeTranslatorTest {
    private val translator = CreationOutcomeTranslator()

    private val confirmed =
        SnippetMetadata(
            id = UUID.randomUUID(),
            name = "Example",
            description = null,
            language = "printscript",
            version = "1.0",
            state = SnippetState.CONFIRMED,
        )

    @Test
    fun `a new snippet is 201 and a repeated one is 200 with the same body`() {
        val created = translator.toResponse(CreationOutcome.Created(confirmed))
        val repeated = translator.toResponse(CreationOutcome.AlreadyCreated(confirmed))

        assertThat(created.statusCode.value()).isEqualTo(201)
        assertThat(repeated.statusCode.value()).isEqualTo(200)
        assertThat(repeated.body).isEqualTo(created.body)
    }

    @Test
    fun `a pending snippet is never answered as created`() {
        val pending = confirmed.copy(state = SnippetState.PENDING)

        assertThat(problemOf(CreationOutcome.Created(pending))).isEqualTo(SnippetProblem.METADATA_UNAVAILABLE)
        assertThat(problemOf(CreationOutcome.AlreadyCreated(pending))).isEqualTo(SnippetProblem.METADATA_UNAVAILABLE)
    }

    @Test
    fun `every rejection has its own problem`() {
        val diagnostic = Diagnostic(rule = "UNEXPECTED_TOKEN", message = "Any wording", line = 2, column = 1)
        val invalidCode = translator.toResponse(rejected(PreparationRejection.InvalidCode(listOf(diagnostic))))

        assertThat(problemOf(rejected(PreparationRejection.InvalidRequest("name"))))
            .isEqualTo(SnippetProblem.INVALID_REQUEST)
        assertThat(problemOf(rejected(PreparationRejection.IdentityRejected(ActorIdentity.Missing))))
            .isEqualTo(SnippetProblem.IDENTITY_UNAVAILABLE)
        assertThat((invalidCode.body as ProblemDetail).properties).containsEntry("diagnostics", listOf(diagnostic))
        assertThat(problemOf(rejected(PreparationRejection.UnsupportedLanguage("python"))))
            .isEqualTo(SnippetProblem.UNSUPPORTED_LANGUAGE)
        assertThat(problemOf(rejected(PreparationRejection.UnsupportedVersion("printscript", "9"))))
            .isEqualTo(SnippetProblem.UNSUPPORTED_VERSION)
        assertThat(problemOf(rejected(PreparationRejection.TechnicalFailure("language"))))
            .isEqualTo(SnippetProblem.DEPENDENCY_FAILURE)
        assertThat(problemOf(rejected(PreparationRejection.TechnicalFailure("metadata"))))
            .isEqualTo(SnippetProblem.METADATA_UNAVAILABLE)
        assertThat(problemOf(CreationOutcome.Conflict)).isEqualTo(SnippetProblem.CREATION_CONFLICT)
    }

    @Test
    fun `an incomplete creation is never answered as a success`() {
        assertThat(problemOf(CreationOutcome.Incomplete(CreationFailure.STORAGE_UNAVAILABLE)))
            .isEqualTo(SnippetProblem.STORAGE_UNAVAILABLE)
        assertThat(problemOf(CreationOutcome.Incomplete(CreationFailure.PERMISSIONS_UNAVAILABLE)))
            .isEqualTo(SnippetProblem.DEPENDENCY_FAILURE)
        assertThat(problemOf(CreationOutcome.Incomplete(CreationFailure.OWNER_CONFLICT)))
            .isEqualTo(SnippetProblem.OWNERSHIP_CONFLICT)
        assertThat(problemOf(CreationOutcome.Incomplete(CreationFailure.METADATA_UNAVAILABLE)))
            .isEqualTo(SnippetProblem.METADATA_UNAVAILABLE)
    }

    @Test
    fun `the documented error table lists exactly the problems the service answers`() {
        val answered =
            SnippetProblem.entries.map { problem ->
                DocumentedProblem(problem.status.value(), "urn:snippets:problem:${problem.category}")
            }

        assertThat(CreationContract.problems).containsExactlyInAnyOrderElementsOf(answered)
    }

    private fun rejected(reason: PreparationRejection): CreationOutcome = CreationOutcome.Rejected(reason)

    private fun problemOf(outcome: CreationOutcome): SnippetProblem {
        val response: ResponseEntity<*> = translator.toResponse(outcome)
        val body = response.body as ProblemDetail

        assertThat(response.statusCode.value()).isEqualTo(body.status)

        return SnippetProblem.entries.single { problem -> problem.body().type == body.type }
    }
}
