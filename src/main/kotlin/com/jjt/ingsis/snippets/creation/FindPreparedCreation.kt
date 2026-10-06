package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.metadata.CreationLookup
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class FindPreparedCreation(
    private val identityResolver: ActorIdentityResolver,
    private val metadata: SnippetMetadataStore,
) {
    fun find(
        keyHeader: String?,
        context: ActorContext,
    ): PreparedCreationLookup {
        val identity = identityResolver.resolve(context)
        if (identity !is ActorIdentity.Identified) {
            return PreparedCreationLookup.Rejected(PreparationRejection.IdentityRejected(identity))
        }
        val key =
            canonicalUuid(keyHeader)
                ?: return PreparedCreationLookup.Rejected(PreparationRejection.InvalidRequest("Idempotency-Key"))
        return lookup(key, identity.actorId)
    }

    private fun lookup(
        key: UUID,
        actorId: String,
    ): PreparedCreationLookup =
        try {
            when (val result = metadata.findCreation(key, actorId)) {
                is CreationLookup.Found -> PreparedCreationLookup.Found(result.metadata)
                CreationLookup.Missing -> PreparedCreationLookup.Missing
                CreationLookup.IdentityConflict -> PreparedCreationLookup.IdentityConflict
            }
        } catch (_: MetadataPersistenceException) {
            PreparedCreationLookup.Rejected(PreparationRejection.TechnicalFailure("metadata"))
        }
}
