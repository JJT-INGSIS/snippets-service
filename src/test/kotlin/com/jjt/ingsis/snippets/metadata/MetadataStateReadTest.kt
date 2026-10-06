package com.jjt.ingsis.snippets.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class)
class MetadataStateReadTest
    @Autowired
    constructor(
        private val store: SnippetMetadataStore,
    ) {
        @Test
        fun `internal lookup includes pending and confirmed state without changing availability rules`() {
            val pending =
                assertInstanceOf(
                    CreationResult.Created::class.java,
                    store.prepareCreation(creationRequest()),
                ).metadata
            assertEquals(pending, store.findMetadata(pending.id))
            assertNull(store.findConfirmed(pending.id))
            val confirmed = checkNotNull(store.confirmCreation(pending.id))
            assertEquals(confirmed, store.findMetadata(pending.id))
            assertEquals(confirmed, store.findConfirmed(pending.id))
            assertNull(store.findMetadata(UUID.randomUUID()))
        }

        @Test
        fun `database failure in state lookup is not a missing record`() {
            val dataSource = mock(DataSource::class.java) { throw SQLException("Database unavailable") }
            val unavailable = JdbcSnippetMetadataStore(JdbcTemplate(dataSource), JdbcTransactionManager(dataSource))
            assertThrows(MetadataPersistenceException::class.java) { unavailable.findMetadata(UUID.randomUUID()) }
        }
    }
