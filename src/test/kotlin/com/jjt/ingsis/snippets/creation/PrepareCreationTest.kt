package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.LanguageValidator
import com.jjt.ingsis.snippets.language.ValidationResult
import com.jjt.ingsis.snippets.metadata.CreationRequest
import com.jjt.ingsis.snippets.metadata.CreationResult
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.metadata.SnippetState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.util.UUID

internal fun language(result: ValidationResult): Language =
    Language(
        listOf(
            object : LanguageValidator {
                override val language = "printscript"

                override fun validate(
                    version: String,
                    code: String,
                ): ValidationResult = result
            },
        ),
    )

internal val identified = ActorIdentityResolver { ActorIdentity.Identified("dev-thiago") }

class PrepareCreationTest {
    private val store = mock(SnippetMetadataStore::class.java)
    private val key = UUID.randomUUID().toString()
    private val context = ActorContext()

    @Test
    fun `reserves pending metadata with backend fingerprint and never confirms or writes ownership`() {
        val metadata = reservedMetadata()
        `when`(store.prepareCreation(request())).thenReturn(CreationResult.Created(metadata))
        val result = preparation().prepare(draft(), key, context)
        assertEquals(PreparationResult.Prepared(metadata, replayed = false), result)
        verify(store).prepareCreation(
            com.jjt.ingsis.snippets.metadata.CreationRequest(
                key = UUID.fromString(key),
                actorId = "dev-thiago",
                fingerprint = CreationFingerprint().calculate(draft()),
                details = draft().details(),
            ),
        )
        verifyNoMoreInteractions(store)
    }

    @Test
    fun `compatible retries retain metadata state and conflicting retries fail`() {
        val metadata = reservedMetadata(SnippetState.CONFIRMED)
        `when`(store.prepareCreation(request())).thenReturn(CreationResult.Existing(metadata), CreationResult.Conflict)
        assertEquals(
            PreparationResult.Prepared(metadata, replayed = true),
            preparation().prepare(draft(), key, context),
        )
        assertEquals(PreparationResult.Conflict, preparation().prepare(draft(), key, context))
    }

    @Test
    fun `request and identity rejection do not access persistence`() {
        assertInstanceOf(PreparationResult.Rejected::class.java, preparation().prepare(draft(), "1-1-1-1-1", context))
        assertEquals(
            PreparationResult.Rejected(PreparationRejection.InvalidRequest("name")),
            preparation().prepare(draft().copy(name = " "), key, context),
        )
        val disabled =
            PrepareCreation(
                identityResolver =
                    ActorIdentityResolver {
                        ActorIdentity.Disabled
                    },
                language = language(ValidationResult.Valid),
                metadata = store,
                fingerprint = CreationFingerprint(),
            )
        assertEquals(
            PreparationResult.Rejected(PreparationRejection.IdentityRejected(ActorIdentity.Disabled)),
            disabled.prepare(draft(), key, context),
        )
        verifyNoInteractions(store)
    }

    @Test
    fun `all language rejections are distinguished before persistence`() {
        val diagnostic = Diagnostic(rule = "UNEXPECTED_TOKEN", message = "Any wording", line = 2, column = 1)
        val cases =
            listOf(
                ValidationResult.Invalid(listOf(diagnostic)) to PreparationRejection.InvalidCode(listOf(diagnostic)),
                ValidationResult.UnsupportedLanguage("other") to PreparationRejection.UnsupportedLanguage("other"),
                ValidationResult.UnsupportedVersion("printscript", "9") to
                    PreparationRejection.UnsupportedVersion("printscript", "9"),
                ValidationResult.Failed("private details") to PreparationRejection.TechnicalFailure("language"),
            )
        cases.forEach { (validation, rejection) ->
            assertEquals(PreparationResult.Rejected(rejection), preparation(validation).prepare(draft(), key, context))
        }
        assertEquals(
            PreparationResult.Rejected(PreparationRejection.UnsupportedLanguage("other")),
            preparation().prepare(draft().copy(language = "other"), key, context),
        )
        verifyNoInteractions(store)
    }

    @Test
    fun `metadata failure never becomes missing conflict or successful reservation`() {
        `when`(
            store.prepareCreation(request()),
        ).thenThrow(MetadataPersistenceException(IllegalStateException("SQL secret")))
        assertEquals(
            PreparationResult.Rejected(PreparationRejection.TechnicalFailure("metadata")),
            preparation().prepare(draft(), key, context),
        )
    }

    private fun preparation(result: ValidationResult = ValidationResult.Valid): PrepareCreation =
        PrepareCreation(
            identityResolver = identified,
            language = language(result),
            metadata = store,
            fingerprint = CreationFingerprint(),
        )

    private fun request(): CreationRequest =
        CreationRequest(
            key = UUID.fromString(key),
            actorId = "dev-thiago",
            fingerprint = CreationFingerprint().calculate(draft()),
            details = draft().details(),
        )
}
