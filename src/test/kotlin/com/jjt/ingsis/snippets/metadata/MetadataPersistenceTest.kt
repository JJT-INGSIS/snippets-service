package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.SnippetsServiceApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class)
class MetadataPersistenceTest
    @Autowired
    constructor(
        private val store: SnippetMetadataStore,
        private val jdbc: JdbcTemplate,
        private val transactionManager: PlatformTransactionManager,
        private val postgres: PostgreSQLContainer,
    ) {
        @Test
        fun pendingCreationIsStoredButNotAvailableAsAConfirmedSnippet() {
            val request = creationRequest()
            val created = assertInstanceOf(CreationResult.Created::class.java, store.prepareCreation(request)).metadata

            assertEquals(SnippetState.PENDING, created.state)
            assertEquals(request.details.name, created.name)
            assertEquals(request.details.description, created.description)
            assertEquals(request.details.language, created.language)
            assertEquals(request.details.version, created.version)
            assertEquals(CreationLookup.Found(created), store.findCreation(request.key, request.actorId))
            assertNull(store.findConfirmed(created.id))

            val confirmed = created.copy(state = SnippetState.CONFIRMED)
            assertEquals(confirmed, store.confirmCreation(created.id))
            assertEquals(confirmed, store.confirmCreation(created.id))
            assertEquals(confirmed, store.findConfirmed(created.id))
        }

        @Test
        fun updatesConfirmedMetadataAndKeepsTheOriginalCreationIdentity() {
            val request = creationRequest()
            val created = assertInstanceOf(CreationResult.Created::class.java, store.prepareCreation(request)).metadata
            val confirmed = checkNotNull(store.confirmCreation(created.id))
            val changes =
                SnippetDetails(
                    name = "Renamed",
                    description = "Updated description",
                    language = "printscript",
                    version = "1.1",
                )
            val expected =
                confirmed.copy(
                    name = changes.name,
                    description = changes.description,
                    language = changes.language,
                    version = changes.version,
                )

            assertEquals(MetadataUpdate.Updated(expected), store.updateMetadata(created.id, changes))
            assertEquals(expected, store.findConfirmed(created.id))
            assertEquals(CreationResult.Existing(expected), store.prepareCreation(request))
            assertEquals(CreationLookup.Found(expected), store.findCreation(request.key, request.actorId))
        }

        @Test
        fun pendingMetadataCannotBeUpdatedThroughTheConfirmedSnippetOperation() {
            val request = creationRequest()
            val created = assertInstanceOf(CreationResult.Created::class.java, store.prepareCreation(request)).metadata

            assertEquals(MetadataUpdate.Pending, store.updateMetadata(created.id, request.details.copy(name = "Other")))
            assertEquals(CreationLookup.Found(created), store.findCreation(request.key, request.actorId))
        }

        @Test
        fun missingRecordsAreReportedWithoutCreatingAnything() {
            val request = creationRequest()
            val id = UUID.randomUUID()

            assertNull(store.findConfirmed(id))
            assertNull(store.confirmCreation(id))
            assertEquals(CreationLookup.Missing, store.findCreation(request.key, request.actorId))
            assertEquals(MetadataUpdate.Missing, store.updateMetadata(id, request.details))
        }

        @Test
        fun migrationsAreAppliedOnce() {
            assertEquals(
                1,
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
                    Int::class.java,
                ),
            )
        }

        @Test
        fun reservationCommitsIndependentlyOfAnOuterTransactionRollback() {
            val request = creationRequest()
            val reserved =
                TransactionTemplate(transactionManager).execute { status ->
                    val metadata =
                        assertInstanceOf(CreationResult.Created::class.java, store.prepareCreation(request)).metadata
                    status.setRollbackOnly()
                    metadata
                }

            assertEquals(CreationResult.Existing(reserved), store.prepareCreation(request))
            assertEquals(CreationLookup.Found(reserved), store.findCreation(request.key, request.actorId))
        }

        @Test
        fun confirmedAndPendingCreationsSurviveApplicationRestart() {
            val pendingRequest = creationRequest()
            val confirmedRequest = creationRequest()
            val (pending, confirmed) =
                newApplication().use { context ->
                    val persistence = context.getBean(SnippetMetadataStore::class.java)
                    val pendingMetadata =
                        assertInstanceOf(
                            CreationResult.Created::class.java,
                            persistence.prepareCreation(pendingRequest),
                        ).metadata
                    val created =
                        assertInstanceOf(
                            CreationResult.Created::class.java,
                            persistence.prepareCreation(confirmedRequest),
                        ).metadata
                    pendingMetadata to checkNotNull(persistence.confirmCreation(created.id))
                }

            newApplication().use { context ->
                val persistence = context.getBean(SnippetMetadataStore::class.java)
                assertEquals(confirmed, persistence.findConfirmed(confirmed.id))
                assertNull(persistence.findConfirmed(pending.id))
                assertEquals(CreationResult.Existing(pending), persistence.prepareCreation(pendingRequest))
                assertEquals(CreationResult.Existing(confirmed), persistence.prepareCreation(confirmedRequest))
            }
        }

        private fun newApplication() =
            SpringApplicationBuilder(SnippetsServiceApplication::class.java)
                .web(WebApplicationType.NONE)
                .run(
                    "--spring.datasource.url=${postgres.jdbcUrl}",
                    "--spring.datasource.username=${postgres.username}",
                    "--spring.datasource.password=${postgres.password}",
                )
    }
