package com.jjt.ingsis.snippets.sharing

import com.jjt.ingsis.snippets.creation.canonicalUuid
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.permissions.ShareRegistrar
import com.jjt.ingsis.snippets.permissions.ShareRegistration
import com.jjt.ingsis.snippets.users.UserDirectory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ShareSnippet(
    private val identityResolver: ActorIdentityResolver,
    private val users: UserDirectory,
    private val metadata: SnippetMetadataStore,
    private val shares: ShareRegistrar,
) {
    fun share(
        snippetId: String?,
        recipientId: String?,
        context: ActorContext,
    ): ShareOutcome {
        val identity = identityResolver.resolve(context)
        if (identity !is ActorIdentity.Identified) return ShareOutcome.IdentityRejected
        val id = canonicalUuid(snippetId) ?: return ShareOutcome.InvalidRequest("snippetId")
        if (recipientId.isNullOrBlank()) return ShareOutcome.InvalidRequest("userId")
        if (recipientId == identity.actorId || !users.contains(recipientId)) return ShareOutcome.InvalidRecipient

        return try {
            if (metadata.findConfirmed(id) == null) {
                ShareOutcome.Missing
            } else {
                register(id, identity.actorId, recipientId)
            }
        } catch (_: MetadataPersistenceException) {
            ShareOutcome.MetadataUnavailable
        }
    }

    private fun register(
        id: UUID,
        actorId: String,
        recipientId: String,
    ): ShareOutcome =
        when (val result = shares.register(id, actorId, recipientId)) {
            is ShareRegistration.Registered -> ShareOutcome.Shared(id, recipientId, result.created)
            ShareRegistration.Denied -> ShareOutcome.Denied
            ShareRegistration.Missing -> ShareOutcome.Missing
            ShareRegistration.InvalidRecipient -> ShareOutcome.InvalidRecipient
            is ShareRegistration.Failed -> ShareOutcome.PermissionsUnavailable
        }
}
