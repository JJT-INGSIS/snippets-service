package com.jjt.ingsis.snippets.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class)
class MetadataFailureTest
    @Autowired
    constructor(
        private val store: SnippetMetadataStore,
        private val jdbc: JdbcTemplate,
    ) {
        @Test
        fun rejectedInsertRollsBackAndDoesNotReserveTheCreationKey() {
            val request = creationRequest()
            jdbc.execute(
                "ALTER TABLE snippets ADD CONSTRAINT reject_test_creation " +
                    "CHECK (creation_fingerprint <> '${request.fingerprint}')",
            )
            try {
                assertThrows(MetadataPersistenceException::class.java) { store.prepareCreation(request) }
                assertEquals(CreationLookup.Missing, store.findCreation(request.key, request.actorId))
            } finally {
                jdbc.execute("ALTER TABLE snippets DROP CONSTRAINT reject_test_creation")
            }
            assertInstanceOf(CreationResult.Created::class.java, store.prepareCreation(request))
        }

        @Test
        fun rejectedUpdatePreservesThePreviousMetadataAndAllowsFollowingOperations() {
            val request = creationRequest()
            val created = assertInstanceOf(CreationResult.Created::class.java, store.prepareCreation(request)).metadata
            val confirmed = checkNotNull(store.confirmCreation(created.id))
            jdbc.execute(
                "ALTER TABLE snippets ADD CONSTRAINT reject_test_update " +
                    "CHECK (id <> '${created.id}'::uuid OR name <> 'Rejected')",
            )
            try {
                assertThrows(MetadataPersistenceException::class.java) {
                    store.updateMetadata(created.id, request.details.copy(name = "Rejected"))
                }
                assertEquals(confirmed, store.findConfirmed(created.id))
            } finally {
                jdbc.execute("ALTER TABLE snippets DROP CONSTRAINT reject_test_update")
            }
            assertInstanceOf(
                MetadataUpdate.Updated::class.java,
                store.updateMetadata(created.id, request.details.copy(name = "Accepted")),
            )
        }

        @Test
        fun rejectedConfirmationLeavesTheCreationPending() {
            val request = creationRequest()
            val created = assertInstanceOf(CreationResult.Created::class.java, store.prepareCreation(request)).metadata
            jdbc.execute(
                "ALTER TABLE snippets ADD CONSTRAINT reject_test_confirmation " +
                    "CHECK (id <> '${created.id}'::uuid OR state <> 'CONFIRMED')",
            )
            try {
                assertThrows(MetadataPersistenceException::class.java) { store.confirmCreation(created.id) }
                assertEquals(CreationLookup.Found(created), store.findCreation(request.key, request.actorId))
            } finally {
                jdbc.execute("ALTER TABLE snippets DROP CONSTRAINT reject_test_confirmation")
            }
            assertEquals(created.copy(state = SnippetState.CONFIRMED), store.confirmCreation(created.id))
        }
    }
