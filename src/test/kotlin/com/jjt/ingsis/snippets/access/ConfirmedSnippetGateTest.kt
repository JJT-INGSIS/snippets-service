package com.jjt.ingsis.snippets.access

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.metadata.ContentLinkResult
import com.jjt.ingsis.snippets.metadata.ContentReplacementResult
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetDetails
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.permissions.AccessChecker
import com.jjt.ingsis.snippets.permissions.AccessLookup
import com.jjt.ingsis.snippets.permissions.AccessRelation
import com.jjt.ingsis.snippets.permissions.PermissionsFailure
import com.jjt.ingsis.snippets.permissions.SnippetAccess
import com.jjt.ingsis.snippets.storage.ContentReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

private val id = UUID.randomUUID()
private val confirmed = linked(SnippetState.CONFIRMED)
private val shared = SnippetAccess(id, "dev-juan", AccessRelation.SHARED)
private val identified = ActorIdentityResolver { ActorIdentity.Identified("dev-thiago") }
private val unasked = AccessChecker { _, _ -> error("Permissions must not be asked") }

private fun linked(state: SnippetState): LinkedSnippet =
    LinkedSnippet(
        SnippetMetadata(
            id = id,
            name = "tested",
            description = null,
            language = "printscript",
            version = "1.0",
            state = state,
        ),
        ContentReference("code"),
    )

private class FoundLinks(
    private val found: () -> LinkedSnippet?,
) : SnippetContentLinks {
    override fun findLinked(id: UUID): LinkedSnippet? = found()

    override fun findContent(id: UUID): ContentReference? = error("The gate only reads the linked snippet")

    override fun linkContent(
        id: UUID,
        reference: ContentReference,
    ): ContentLinkResult = error("The gate never links content")

    override fun replaceContent(
        id: UUID,
        details: SnippetDetails,
        reference: ContentReference,
    ): ContentReplacementResult = error("The gate never replaces content")
}

private fun gate(
    found: () -> LinkedSnippet? = { confirmed },
    access: AccessChecker = AccessChecker { _, _ -> AccessLookup.Granted(shared) },
    identity: ActorIdentityResolver = identified,
): ReadGate = ConfirmedSnippetGate(identity, FoundLinks(found), access)

class ConfirmedSnippetGateTest {
    @Test
    fun `a confirmed snippet the actor can read is granted with its relation`() {
        assertEquals(ReadDecision.Granted(confirmed, shared), gate().open(id.toString(), ActorContext()))
    }

    @Test
    fun `identity and id are checked before reading anything`() {
        val unread = { error("Metadata must not be read") }
        val anonymous = ActorIdentityResolver { ActorIdentity.Missing }

        assertEquals(
            ReadDecision.Refused(ReadFailure.IDENTITY_REJECTED),
            gate(unread, unasked, anonymous).open(id.toString(), ActorContext()),
        )
        assertEquals(
            ReadDecision.Refused(ReadFailure.INVALID_ID),
            gate(unread, unasked).open("1-1-1-1-1", ActorContext()),
        )
    }

    @Test
    fun `missing or pending snippets are refused without asking Permissions`() {
        listOf(null, linked(SnippetState.PENDING)).forEach { snippet ->
            assertEquals(
                ReadDecision.Refused(ReadFailure.SNIPPET_MISSING),
                gate({ snippet }, unasked).open(id.toString(), ActorContext()),
            )
        }
    }

    @Test
    fun `no access or no owner looks exactly like a missing snippet`() {
        listOf(AccessLookup.Denied, AccessLookup.Missing).forEach { lookup ->
            assertEquals(
                ReadDecision.Refused(ReadFailure.SNIPPET_MISSING),
                gate(access = { _, _ -> lookup }).open(id.toString(), ActorContext()),
            )
        }
    }

    @Test
    fun `technical failures keep their own reason`() {
        val permissionsDown = AccessChecker { _, _ -> AccessLookup.Failed(PermissionsFailure.UNAVAILABLE) }
        val databaseDown = { throw MetadataPersistenceException(IllegalStateException("down")) }

        assertEquals(
            ReadDecision.Refused(ReadFailure.PERMISSIONS_UNAVAILABLE),
            gate(access = permissionsDown).open(id.toString(), ActorContext()),
        )
        assertEquals(
            ReadDecision.Refused(ReadFailure.METADATA_UNAVAILABLE),
            gate(databaseDown, unasked).open(id.toString(), ActorContext()),
        )
    }
}
