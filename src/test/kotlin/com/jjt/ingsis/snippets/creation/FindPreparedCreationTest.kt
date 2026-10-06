package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.metadata.CreationLookup
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.UUID

class FindPreparedCreationTest {
    private val store = mock(SnippetMetadataStore::class.java)
    private val key = UUID.randomUUID()

    @Test
    fun `returns pending or confirmed metadata without declaring a successful creation`() {
        val metadata = reservedMetadata()
        `when`(store.findCreation(key, "dev-thiago")).thenReturn(CreationLookup.Found(metadata))
        assertEquals(PreparedCreationLookup.Found(metadata), lookup())
    }

    @Test
    fun `missing identity conflict and database failure remain distinct`() {
        `when`(store.findCreation(key, "dev-thiago"))
            .thenReturn(CreationLookup.Missing, CreationLookup.IdentityConflict)
            .thenThrow(MetadataPersistenceException(IllegalStateException("private SQL")))
        assertEquals(PreparedCreationLookup.Missing, lookup())
        assertEquals(PreparedCreationLookup.IdentityConflict, lookup())
        assertEquals(PreparedCreationLookup.Rejected(PreparationRejection.TechnicalFailure("metadata")), lookup())
    }

    @Test
    fun `invalid input cannot query another creation`() {
        val finder = FindPreparedCreation(identified, store)
        assertEquals(
            PreparedCreationLookup.Rejected(PreparationRejection.InvalidRequest("Idempotency-Key")),
            finder.find(null, ActorContext()),
        )
        val missing = FindPreparedCreation(ActorIdentityResolver { ActorIdentity.Missing }, store)
        assertEquals(
            PreparedCreationLookup.Rejected(PreparationRejection.IdentityRejected(ActorIdentity.Missing)),
            missing.find(key.toString(), ActorContext()),
        )
        verifyNoInteractions(store)
    }

    private fun lookup(): PreparedCreationLookup =
        FindPreparedCreation(identified, store).find(key.toString(), ActorContext())
}
