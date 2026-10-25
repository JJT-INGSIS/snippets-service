package com.jjt.ingsis.snippets.sharing

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.permissions.PermissionsFailure
import com.jjt.ingsis.snippets.permissions.ShareRegistrar
import com.jjt.ingsis.snippets.permissions.ShareRegistration
import com.jjt.ingsis.snippets.users.UserDirectory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.UUID

private const val OWNER = "dev-juan"
private const val RECIPIENT = "dev-ana"
private val id = UUID.fromString("0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40")
private val confirmed =
    SnippetMetadata(
        id = id,
        name = "Example",
        description = null,
        language = "printscript",
        version = "1.0",
        state = SnippetState.CONFIRMED,
    )

class ShareSnippetTest {
    private val metadata = mock(SnippetMetadataStore::class.java)
    private val users = mock(UserDirectory::class.java)

    @Test
    fun `only a confirmed registration reports a share and preserves the exact identities`() {
        ready()
        val received = mutableListOf<List<String>>()
        val operation =
            sharing(
                ShareRegistrar { snippet, actor, recipient ->
                    received.add(listOf(snippet.toString(), actor, recipient))
                    ShareRegistration.Registered(created = received.size == 1)
                },
            )
        assertThat(operation.share(id.toString(), RECIPIENT, ActorContext()))
            .isEqualTo(ShareOutcome.Shared(id, RECIPIENT, created = true))
        assertThat(operation.share(id.toString(), RECIPIENT, ActorContext()))
            .isEqualTo(ShareOutcome.Shared(id, RECIPIENT, created = false))
        assertThat(
            received,
        ).containsExactly(listOf(id.toString(), OWNER, RECIPIENT), listOf(id.toString(), OWNER, RECIPIENT))
    }

    @Test
    fun `missing invalid and disabled identities cannot query users or metadata`() {
        listOf(ActorIdentity.Missing, ActorIdentity.Invalid, ActorIdentity.Disabled).forEach { identity ->
            assertThat(
                sharing(identity = ActorIdentityResolver { identity }).share(id.toString(), RECIPIENT, ActorContext()),
            ).isEqualTo(ShareOutcome.IdentityRejected)
        }
        verifyNoInteractions(users, metadata)
    }

    @Test
    fun `malformed identifiers never reach a dependency`() {
        listOf(null, "bad-id", "1-1-1-1-1").forEach { invalid ->
            assertThat(
                sharing().share(invalid, RECIPIENT, ActorContext()),
            ).isEqualTo(ShareOutcome.InvalidRequest("snippetId"))
        }
        assertThat(sharing().share(id.toString(), " ", ActorContext())).isEqualTo(ShareOutcome.InvalidRequest("userId"))
        verifyNoInteractions(users, metadata)
    }

    @Test
    fun `self and unknown recipients cannot grant access`() {
        assertThat(sharing().share(id.toString(), OWNER, ActorContext())).isEqualTo(ShareOutcome.InvalidRecipient)
        assertThat(sharing().share(id.toString(), RECIPIENT, ActorContext())).isEqualTo(ShareOutcome.InvalidRecipient)
        verifyNoInteractions(metadata)
    }

    @Test
    fun `a missing or unconfirmed snippet cannot reach permissions`() {
        `when`(users.contains(RECIPIENT)).thenReturn(true)
        assertThat(sharing().share(id.toString(), RECIPIENT, ActorContext())).isEqualTo(ShareOutcome.Missing)
    }

    @Test
    fun `a metadata failure is never absence or success`() {
        `when`(users.contains(RECIPIENT)).thenReturn(true)
        `when`(
            metadata.findConfirmed(id),
        ).thenThrow(MetadataPersistenceException(IllegalStateException("database down")))
        assertThat(
            sharing().share(id.toString(), RECIPIENT, ActorContext()),
        ).isEqualTo(ShareOutcome.MetadataUnavailable)
    }

    @Test
    fun `permission outcomes retain their meaning without retries or compensating deletes`() {
        ready()
        val outcomes =
            mapOf(
                ShareRegistration.Denied to ShareOutcome.Denied,
                ShareRegistration.Missing to ShareOutcome.Missing,
                ShareRegistration.InvalidRecipient to ShareOutcome.InvalidRecipient,
                ShareRegistration.Failed(PermissionsFailure.UNAVAILABLE) to ShareOutcome.PermissionsUnavailable,
                ShareRegistration.Failed(PermissionsFailure.INVALID_RESPONSE) to ShareOutcome.PermissionsUnavailable,
            )
        outcomes.forEach { (registration, expected) ->
            var attempts = 0
            val operation =
                sharing(
                    ShareRegistrar { _, _, _ ->
                        attempts++
                        registration
                    },
                )
            assertThat(operation.share(id.toString(), RECIPIENT, ActorContext())).isEqualTo(expected)
            assertThat(attempts).isEqualTo(1)
        }
    }

    private fun ready() {
        `when`(users.contains(RECIPIENT)).thenReturn(true)
        `when`(metadata.findConfirmed(id)).thenReturn(confirmed)
    }

    private fun sharing(
        registrar: ShareRegistrar = ShareRegistrar { _, _, _ -> error("No permission may be written") },
        identity: ActorIdentityResolver = ActorIdentityResolver { ActorIdentity.Identified(OWNER) },
    ): ShareSnippet = ShareSnippet(identityResolver = identity, users = users, metadata = metadata, shares = registrar)
}
