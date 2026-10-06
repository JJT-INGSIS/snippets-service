package com.jjt.ingsis.snippets.metadata

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

class MetadataTechnicalFailureTest {
    @Test
    fun unavailableDatabaseIsNeitherAMissingRecordNorASuccessfulWrite() {
        val dataSource = mock(DataSource::class.java) { throw SQLException("Database unavailable") }
        val store = JdbcSnippetMetadataStore(JdbcTemplate(dataSource), JdbcTransactionManager(dataSource))
        val request = creationRequest()
        val id = UUID.randomUUID()

        assertThrows(MetadataPersistenceException::class.java) { store.findConfirmed(id) }
        assertThrows(MetadataPersistenceException::class.java) { store.findCreation(request.key, request.actorId) }
        assertThrows(MetadataPersistenceException::class.java) { store.prepareCreation(request) }
        assertThrows(MetadataPersistenceException::class.java) { store.confirmCreation(id) }
        assertThrows(MetadataPersistenceException::class.java) { store.updateMetadata(id, request.details) }
    }

    @Test
    fun commitFailureIsPropagatedInsteadOfReturningTheTransactionResult() {
        val transaction = mock(PlatformTransactionManager::class.java)
        doReturn(SimpleTransactionStatus()).`when`(transaction).getTransaction(any(TransactionDefinition::class.java))
        doThrow(TransactionSystemException("Commit failed"))
            .`when`(transaction)
            .commit(any(TransactionStatus::class.java))
        val store = JdbcSnippetMetadataStore(mock(JdbcTemplate::class.java), transaction)

        assertThrows(MetadataPersistenceException::class.java) { store.confirmCreation(UUID.randomUUID()) }
    }
}
