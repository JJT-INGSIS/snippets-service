package com.jjt.ingsis.snippets.metadata

import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionException
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.util.UUID

@Repository
class JdbcSnippetMetadataStore(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) : SnippetMetadataStore {
    private val transaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    override fun prepareCreation(request: CreationRequest): CreationResult =
        inTransaction {
            val inserted = insertPending(request)
            val stored = checkNotNull(findStoredCreation(request.key))
            when {
                inserted -> {
                    CreationResult.Created(stored.metadata)
                }

                stored.actorId == request.actorId && stored.fingerprint == request.fingerprint -> {
                    CreationResult.Existing(stored.metadata)
                }

                else -> {
                    CreationResult.Conflict
                }
            }
        }

    override fun findCreation(
        key: UUID,
        actorId: String,
    ): CreationLookup {
        require(actorId.isNotBlank()) { "The creation actor must not be blank" }
        return databaseOperation {
            val stored = findStoredCreation(key)
            when {
                stored == null -> CreationLookup.Missing
                stored.actorId != actorId -> CreationLookup.IdentityConflict
                else -> CreationLookup.Found(stored.metadata)
            }
        }
    }

    override fun findConfirmed(id: UUID): SnippetMetadata? =
        databaseOperation {
            jdbc
                .query(
                    "SELECT * FROM snippets WHERE id = ? AND state = 'CONFIRMED'",
                    { row, _ -> row.toMetadata() },
                    id,
                ).singleOrNull()
        }

    override fun confirmCreation(id: UUID): SnippetMetadata? =
        databaseOperation {
            transaction.execute {
                jdbc.update("UPDATE snippets SET state = 'CONFIRMED' WHERE id = ? AND state = 'PENDING'", id)
                findMetadata(id)
            }
        }

    override fun updateMetadata(
        id: UUID,
        details: SnippetDetails,
    ): MetadataUpdate =
        inTransaction {
            val metadata =
                jdbc
                    .query(
                        "SELECT * FROM snippets WHERE id = ? FOR UPDATE",
                        { row, _ -> row.toMetadata() },
                        id,
                    ).singleOrNull()
            if (metadata == null) {
                return@inTransaction MetadataUpdate.Missing
            }
            if (metadata.state == SnippetState.PENDING) {
                return@inTransaction MetadataUpdate.Pending
            }
            jdbc.update(
                "UPDATE snippets SET name = ?, description = ?, language = ?, version = ? WHERE id = ?",
                details.name,
                details.description,
                details.language,
                details.version,
                metadata.id,
            )
            MetadataUpdate.Updated(
                metadata.copy(
                    name = details.name,
                    description = details.description,
                    language = details.language,
                    version = details.version,
                ),
            )
        }

    private fun insertPending(request: CreationRequest): Boolean =
        jdbc.update(
            "INSERT INTO snippets " +
                "(id, name, description, language, version, state, " +
                "creation_key, creation_actor_id, creation_fingerprint) " +
                "VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?) ON CONFLICT (creation_key) DO NOTHING",
            UUID.randomUUID(),
            request.details.name,
            request.details.description,
            request.details.language,
            request.details.version,
            request.key,
            request.actorId,
            request.fingerprint,
        ) == 1

    private fun findStoredCreation(key: UUID): StoredCreation? =
        jdbc
            .query(
                "SELECT * FROM snippets WHERE creation_key = ?",
                { row, _ ->
                    StoredCreation(
                        metadata = row.toMetadata(),
                        actorId = row.getString("creation_actor_id"),
                        fingerprint = row.getString("creation_fingerprint"),
                    )
                },
                key,
            ).singleOrNull()

    private fun findMetadata(id: UUID): SnippetMetadata? =
        jdbc.query("SELECT * FROM snippets WHERE id = ?", { row, _ -> row.toMetadata() }, id).singleOrNull()

    private fun <T : Any> inTransaction(operation: () -> T): T =
        databaseOperation { transaction.execute { operation() } }

    private fun <T> databaseOperation(operation: () -> T): T =
        try {
            operation()
        } catch (exception: DataAccessException) {
            throw MetadataPersistenceException(exception)
        } catch (exception: TransactionException) {
            throw MetadataPersistenceException(exception)
        }
}

private data class StoredCreation(
    val metadata: SnippetMetadata,
    val actorId: String,
    val fingerprint: String,
)

private fun ResultSet.toMetadata(): SnippetMetadata =
    SnippetMetadata(
        id = getObject("id", UUID::class.java),
        name = getString("name"),
        description = getString("description"),
        language = getString("language"),
        version = getString("version"),
        state = SnippetState.valueOf(getString("state")),
    )
