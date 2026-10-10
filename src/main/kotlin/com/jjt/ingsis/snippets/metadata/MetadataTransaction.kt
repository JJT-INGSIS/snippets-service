package com.jjt.ingsis.snippets.metadata

import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionException
import org.springframework.transaction.support.TransactionTemplate

@Component
class MetadataTransaction(
    transactionManager: PlatformTransactionManager,
) {
    private val independent =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    fun <T : Any> commit(operation: () -> T): T = read { independent.execute { operation() } }

    fun <T> read(operation: () -> T): T =
        try {
            operation()
        } catch (failure: DataAccessException) {
            throw MetadataPersistenceException(failure)
        } catch (failure: TransactionException) {
            throw MetadataPersistenceException(failure)
        }
}
