package com.jjt.ingsis.snippets.update

import com.jjt.ingsis.snippets.creation.PreparationRejection
import com.jjt.ingsis.snippets.creation.draft
import com.jjt.ingsis.snippets.creation.identified
import com.jjt.ingsis.snippets.creation.reservedMetadata
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.ValidationResult
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.permissions.ModificationChecker
import com.jjt.ingsis.snippets.permissions.ModificationPermission
import com.jjt.ingsis.snippets.permissions.PermissionsFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`

class PrepareUpdateTest {
    private val existing = reservedMetadata(SnippetState.CONFIRMED)
    private val store = mock(SnippetMetadataStore::class.java)
    private val checker = mock(ModificationChecker::class.java)
    private val language = mock(Language::class.java)
    private val context = ActorContext()

    init {
        `when`(store.findMetadata(existing.id)).thenReturn(existing)
        `when`(checker.check(existing.id, "dev-thiago")).thenReturn(ModificationPermission.Allowed)
    }

    @Test
    fun `valid full replacement returns a candidate without performing any write`() {
        val replacement = draft().copy(name = "Renamed", description = null, version = "1.1", code = "println(2);")
        `when`(
            language.validate(replacement.language, replacement.version, replacement.code),
        ).thenReturn(ValidationResult.Valid)
        assertEquals(
            UpdatePreparationResult.Prepared(UpdateCandidate(existing.id, replacement)),
            prepare().prepare(replacement, existing.id.toString(), context),
        )
        verify(store).findMetadata(existing.id)
        verify(checker).check(existing.id, "dev-thiago")
        verify(language).validate(replacement.language, replacement.version, replacement.code)
        verifyNoMoreInteractions(store, checker, language)
    }

    @Test
    fun `replacement language version and code are validated instead of stored values`() {
        val replacement = draft().copy(language = "other", version = "2", code = "new code")
        `when`(language.validate("other", "2", "new code")).thenReturn(ValidationResult.UnsupportedLanguage("other"))
        assertEquals(
            UpdatePreparationResult.Rejected(PreparationRejection.UnsupportedLanguage("other")),
            prepare().prepare(replacement, existing.id.toString(), context),
        )
        verify(language).validate("other", "2", "new code")
        verify(store).findMetadata(existing.id)
        verifyNoMoreInteractions(store, language)
    }

    @Test
    fun `missing and pending snippets are distinguished before permission or validation`() {
        `when`(store.findMetadata(existing.id)).thenReturn(null, existing.copy(state = SnippetState.PENDING))
        assertEquals(UpdatePreparationResult.Missing, attempt())
        assertEquals(UpdatePreparationResult.Pending, attempt())
        verifyNoInteractions(checker, language)
    }

    @Test
    fun `denied missing ownership and technical failure do not validate or write`() {
        `when`(checker.check(existing.id, "dev-thiago")).thenReturn(
            ModificationPermission.Denied,
            ModificationPermission.OwnershipMissing,
            ModificationPermission.Failed(PermissionsFailure.UNAVAILABLE),
        )
        assertEquals(UpdatePreparationResult.Denied, attempt())
        assertEquals(UpdatePreparationResult.OwnershipMissing, attempt())
        assertEquals(UpdatePreparationResult.Rejected(PreparationRejection.TechnicalFailure("permissions")), attempt())
        verifyNoInteractions(language)
        verify(store, org.mockito.Mockito.times(3)).findMetadata(existing.id)
        verifyNoMoreInteractions(store)
    }

    @Test
    fun `all validation failures are typed and preserve diagnostics`() {
        val diagnostics =
            listOf(
                Diagnostic(rule = "UNEXPECTED_TOKEN", message = "Any wording", line = 2, column = 1),
                Diagnostic(rule = "OTHER", message = "Another diagnostic", line = 3, column = 4),
            )
        val cases =
            listOf(
                ValidationResult.Invalid(diagnostics) to PreparationRejection.InvalidCode(diagnostics),
                ValidationResult.UnsupportedLanguage("other") to PreparationRejection.UnsupportedLanguage("other"),
                ValidationResult.UnsupportedVersion("printscript", "9") to
                    PreparationRejection.UnsupportedVersion("printscript", "9"),
                ValidationResult.Failed("private details") to PreparationRejection.TechnicalFailure("language"),
            )
        cases.forEach { (validation, rejection) ->
            `when`(language.validate("printscript", "1.0", "println(1);")).thenReturn(validation)
            assertEquals(UpdatePreparationResult.Rejected(rejection), attempt())
        }
        verify(store, org.mockito.Mockito.times(cases.size)).findMetadata(existing.id)
        verifyNoMoreInteractions(store)
    }

    @Test
    fun `invalid fields identity or UUID cannot access existing state`() {
        assertEquals(
            UpdatePreparationResult.Rejected(PreparationRejection.InvalidRequest("snippetId")),
            prepare().prepare(draft(), "1-1-1-1-1", context),
        )
        assertEquals(
            UpdatePreparationResult.Rejected(PreparationRejection.InvalidRequest("name")),
            prepare().prepare(draft().copy(name = " "), existing.id.toString(), context),
        )
        val disabled =
            PrepareUpdate(
                identityResolver = ActorIdentityResolver { ActorIdentity.Disabled },
                metadata = store,
                permissions = checker,
                language = language,
            )
        assertEquals(
            UpdatePreparationResult.Rejected(PreparationRejection.IdentityRejected(ActorIdentity.Disabled)),
            disabled.prepare(draft(), existing.id.toString(), context),
        )
        verifyNoInteractions(store, checker, language)
    }

    @Test
    fun `metadata error does not become missing or pending`() {
        `when`(
            store.findMetadata(existing.id),
        ).thenThrow(MetadataPersistenceException(IllegalStateException("private SQL")))
        assertEquals(UpdatePreparationResult.Rejected(PreparationRejection.TechnicalFailure("metadata")), attempt())
        verifyNoInteractions(checker, language)
    }

    private fun prepare(): PrepareUpdate =
        PrepareUpdate(identityResolver = identified, metadata = store, permissions = checker, language = language)

    private fun attempt(): UpdatePreparationResult = prepare().prepare(draft(), existing.id.toString(), context)
}
