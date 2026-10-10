package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.language.ValidationResult
import com.jjt.ingsis.snippets.metadata.SnippetMetadata

sealed interface PreparationRejection {
    data class InvalidRequest(
        val field: String,
    ) : PreparationRejection

    data class IdentityRejected(
        val identity: ActorIdentity,
    ) : PreparationRejection

    data class InvalidCode(
        val diagnostics: List<Diagnostic>,
    ) : PreparationRejection

    data class UnsupportedLanguage(
        val language: String,
    ) : PreparationRejection

    data class UnsupportedVersion(
        val language: String,
        val version: String,
    ) : PreparationRejection

    data class TechnicalFailure(
        val component: String,
    ) : PreparationRejection
}

internal fun ValidationResult.rejection(): PreparationRejection? =
    when (this) {
        ValidationResult.Valid -> null
        is ValidationResult.Invalid -> PreparationRejection.InvalidCode(diagnostics)
        is ValidationResult.UnsupportedLanguage -> PreparationRejection.UnsupportedLanguage(language)
        is ValidationResult.UnsupportedVersion -> PreparationRejection.UnsupportedVersion(language, version)
        is ValidationResult.Failed -> PreparationRejection.TechnicalFailure("language")
    }

sealed interface PreparationResult {
    // This is an internal reservation result, never a public successful creation.
    data class Prepared(
        val metadata: SnippetMetadata,
        val replayed: Boolean,
        val actorId: String,
    ) : PreparationResult

    data class Rejected(
        val reason: PreparationRejection,
    ) : PreparationResult

    data object Conflict : PreparationResult
}

sealed interface PreparedCreationLookup {
    data class Found(
        val metadata: SnippetMetadata,
    ) : PreparedCreationLookup

    data class Rejected(
        val reason: PreparationRejection,
    ) : PreparedCreationLookup

    data object Missing : PreparedCreationLookup

    data object IdentityConflict : PreparedCreationLookup
}
