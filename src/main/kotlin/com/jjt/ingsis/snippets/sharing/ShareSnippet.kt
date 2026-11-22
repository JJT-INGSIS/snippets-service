package com.jjt.ingsis.snippets.sharing

import com.jjt.ingsis.snippets.creation.canonicalUuid
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.permissions.ShareRegistrar
import com.jjt.ingsis.snippets.permissions.ShareRegistration
import com.jjt.ingsis.snippets.users.DirectoryMembership
import com.jjt.ingsis.snippets.users.UserDirectory
import com.newrelic.api.agent.Trace
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ShareSnippet(
    private val identityResolver: ActorIdentityResolver,
    private val users: UserDirectory,
    private val metadata: SnippetMetadataStore,
    private val shares: ShareRegistrar,
) {
    private val logger = LoggerFactory.getLogger(ShareSnippet::class.java)

    @Trace(metricName = "Snippets/sharing")
    fun share(
        snippetId: String?,
        recipientId: String?,
        context: ActorContext,
    ): ShareOutcome {
        val identity = identityResolver.resolve(context)
        if (identity !is ActorIdentity.Identified) return ShareOutcome.IdentityRejected
        val id = canonicalUuid(snippetId) ?: return ShareOutcome.InvalidRequest("snippetId")
        if (recipientId.isNullOrBlank()) return ShareOutcome.InvalidRequest("userId")
        return recipientRejection(identity.actorId, recipientId)
            ?: shareConfirmedSnippet(id, identity.actorId, recipientId)
    }

    private fun recipientRejection(
        actorId: String,
        recipientId: String,
    ): ShareOutcome? =
        if (recipientId == actorId) {
            ShareOutcome.InvalidRecipient
        } else {
            when (users.contains(recipientId)) {
                DirectoryMembership.ABSENT -> ShareOutcome.InvalidRecipient
                DirectoryMembership.UNAVAILABLE -> ShareOutcome.DirectoryUnavailable
                DirectoryMembership.PRESENT -> null
            }
        }

    private fun shareConfirmedSnippet(
        id: UUID,
        actorId: String,
        recipientId: String,
    ): ShareOutcome =
        try {
            if (metadata.findConfirmed(id) == null) {
                ShareOutcome.Missing
            } else {
                registerShareInPermissions(id, actorId, recipientId)
            }
        } catch (_: MetadataPersistenceException) {
            ShareOutcome.MetadataUnavailable
        }

    private fun registerShareInPermissions(
        id: UUID,
        actorId: String,
        recipientId: String,
    ): ShareOutcome =
        when (val result = shares.register(id, actorId, recipientId)) {
            is ShareRegistration.Registered -> {
                logger.info("Snippet {} is shared with a recipient (new share: {})", id, result.created)
                ShareOutcome.Shared(id, recipientId, result.created)
            }

            ShareRegistration.Denied -> {
                ShareOutcome.Denied
            }

            ShareRegistration.Missing -> {
                ShareOutcome.Missing
            }

            ShareRegistration.InvalidRecipient -> {
                ShareOutcome.InvalidRecipient
            }

            is ShareRegistration.Failed -> {
                ShareOutcome.PermissionsUnavailable
            }
        }
}
