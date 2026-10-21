package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.metadata.CreationRequest
import com.jjt.ingsis.snippets.metadata.CreationResult
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import org.springframework.stereotype.Service

@Service
class PrepareCreation(
    private val identityResolver: ActorIdentityResolver,
    private val language: Language,
    private val metadata: SnippetMetadataStore,
    private val fingerprint: CreationFingerprint,
) {
    fun prepare(
        draft: SnippetDraft,
        keyHeader: String?,
        context: ActorContext,
    ): PreparationResult {
        val identity = identityResolver.resolve(context)
        if (identity !is ActorIdentity.Identified) {
            return PreparationResult.Rejected(PreparationRejection.IdentityRejected(identity))
        }
        val key =
            canonicalUuid(keyHeader)
                ?: return PreparationResult.Rejected(PreparationRejection.InvalidRequest("Idempotency-Key"))
        val invalidField = draft.invalidField()
        if (invalidField != null) {
            return PreparationResult.Rejected(PreparationRejection.InvalidRequest(invalidField))
        }
        val rejection = language.validate(draft.language, draft.version, draft.code).rejection()
        if (rejection != null) return PreparationResult.Rejected(rejection)
        val request =
            CreationRequest(
                key = key,
                actorId = identity.actorId,
                fingerprint = fingerprint.calculate(draft),
                details = draft.details(),
            )
        return reserve(request)
    }

    private fun reserve(request: CreationRequest): PreparationResult =
        try {
            when (val result = metadata.prepareCreation(request)) {
                is CreationResult.Created -> prepared(result.metadata, request, replayed = false)
                is CreationResult.Existing -> prepared(result.metadata, request, replayed = true)
                CreationResult.Conflict -> PreparationResult.Conflict
            }
        } catch (_: MetadataPersistenceException) {
            PreparationResult.Rejected(PreparationRejection.TechnicalFailure("metadata"))
        }

    private fun prepared(
        reserved: SnippetMetadata,
        request: CreationRequest,
        replayed: Boolean,
    ): PreparationResult =
        PreparationResult.Prepared(metadata = reserved, replayed = replayed, actorId = request.actorId)
}
