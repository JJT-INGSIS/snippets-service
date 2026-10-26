package com.jjt.ingsis.snippets.access

import com.jjt.ingsis.snippets.creation.canonicalUuid
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.permissions.AccessChecker
import com.jjt.ingsis.snippets.permissions.AccessLookup
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ConfirmedSnippetGate(
    private val identityResolver: ActorIdentityResolver,
    private val links: SnippetContentLinks,
    private val accessChecker: AccessChecker,
) : ReadGate {
    override fun open(
        snippetId: String?,
        context: ActorContext,
    ): ReadDecision {
        val identity = identityResolver.resolve(context)
        if (identity !is ActorIdentity.Identified) return refused(ReadFailure.IDENTITY_REJECTED)
        val id = canonicalUuid(snippetId) ?: return refused(ReadFailure.INVALID_ID)

        return try {
            authorize(id, identity.actorId)
        } catch (_: MetadataPersistenceException) {
            refused(ReadFailure.METADATA_UNAVAILABLE)
        }
    }

    private fun authorize(
        id: UUID,
        actorId: String,
    ): ReadDecision {
        val snippet =
            links.findLinked(id)?.takeIf { it.metadata.state == SnippetState.CONFIRMED }
                ?: return refused(ReadFailure.SNIPPET_MISSING)

        return when (val lookup = accessChecker.check(id, actorId)) {
            is AccessLookup.Granted -> ReadDecision.Granted(snippet, lookup.access)
            AccessLookup.Denied, AccessLookup.Missing -> refused(ReadFailure.SNIPPET_MISSING)
            is AccessLookup.Failed -> refused(ReadFailure.PERMISSIONS_UNAVAILABLE)
        }
    }

    private fun refused(failure: ReadFailure): ReadDecision = ReadDecision.Refused(failure)
}
