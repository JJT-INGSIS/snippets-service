package com.jjt.ingsis.snippets.update

import com.jjt.ingsis.snippets.creation.PreparationRejection
import com.jjt.ingsis.snippets.creation.SnippetDraft
import com.jjt.ingsis.snippets.creation.canonicalUuid
import com.jjt.ingsis.snippets.creation.rejection
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.permissions.ModificationChecker
import com.jjt.ingsis.snippets.permissions.ModificationPermission
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class PrepareUpdate(
    private val identityResolver: ActorIdentityResolver,
    private val metadata: SnippetMetadataStore,
    private val permissions: ModificationChecker,
    private val language: Language,
) {
    fun prepare(
        draft: SnippetDraft,
        snippetId: String?,
        context: ActorContext,
    ): UpdatePreparationResult {
        val identity = identityResolver.resolve(context)
        if (identity !is ActorIdentity.Identified) {
            return UpdatePreparationResult.Rejected(PreparationRejection.IdentityRejected(identity))
        }
        val id =
            canonicalUuid(snippetId)
                ?: return UpdatePreparationResult.Rejected(PreparationRejection.InvalidRequest("snippetId"))
        val invalidField = draft.invalidField()
        if (invalidField != null) {
            return UpdatePreparationResult.Rejected(
                PreparationRejection.InvalidRequest(invalidField),
            )
        }
        return lookupAndPrepare(id, identity.actorId, draft)
    }

    private fun lookupAndPrepare(
        id: UUID,
        actorId: String,
        draft: SnippetDraft,
    ): UpdatePreparationResult =
        try {
            val existing = metadata.findMetadata(id)
            when (existing?.state) {
                null -> UpdatePreparationResult.Missing
                SnippetState.PENDING -> UpdatePreparationResult.Pending
                SnippetState.CONFIRMED -> authorize(id, actorId, draft)
            }
        } catch (_: MetadataPersistenceException) {
            UpdatePreparationResult.Rejected(PreparationRejection.TechnicalFailure("metadata"))
        }

    private fun authorize(
        id: UUID,
        actorId: String,
        draft: SnippetDraft,
    ): UpdatePreparationResult =
        when (permissions.check(id, actorId)) {
            ModificationPermission.Allowed -> {
                validate(id, draft)
            }

            ModificationPermission.Denied -> {
                UpdatePreparationResult.Denied
            }

            ModificationPermission.OwnershipMissing -> {
                UpdatePreparationResult.OwnershipMissing
            }

            is ModificationPermission.Failed -> {
                UpdatePreparationResult.Rejected(PreparationRejection.TechnicalFailure("permissions"))
            }
        }

    private fun validate(
        id: UUID,
        draft: SnippetDraft,
    ): UpdatePreparationResult {
        val rejection = language.validate(draft.language, draft.version, draft.code).rejection()
        return if (rejection == null) {
            UpdatePreparationResult.Prepared(UpdateCandidate(id, draft))
        } else {
            UpdatePreparationResult.Rejected(rejection)
        }
    }
}
