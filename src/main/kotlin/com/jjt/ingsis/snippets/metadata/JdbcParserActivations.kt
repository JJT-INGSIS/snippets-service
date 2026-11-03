package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.background.NewBackgroundJob
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.revalidation.ParserActivation
import com.jjt.ingsis.snippets.revalidation.ParserActivationRequest
import com.jjt.ingsis.snippets.revalidation.ParserActivationResult
import com.jjt.ingsis.snippets.revalidation.ParserActivations
import com.jjt.ingsis.snippets.revalidation.ParserScope
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Repository
class JdbcParserActivations(
    private val jdbc: JdbcTemplate,
    private val transaction: MetadataTransaction,
    private val jobs: JdbcJobRecords,
) : ParserActivations {
    override fun replay(request: ParserActivationRequest): ParserActivationResult? =
        transaction.read {
            jdbc
                .query("SELECT * FROM parser_activations WHERE id = ?", { row, _ ->
                    val activation = read(row)
                    if (activation.scope == request.scope && activation.processor == request.processor &&
                        row.getString("reason") == request.reason
                    ) {
                        ParserActivationResult.Confirmed(activation, replayed = true)
                    } else {
                        ParserActivationResult.RequestConflict
                    }
                }, request.requestId)
                .singleOrNull()
        }

    override fun active(scope: ParserScope): ParserActivation? =
        transaction.read {
            jdbc
                .query(
                    "SELECT a.* FROM parser_activations a JOIN parser_activation_scopes s " +
                        "ON s.active_activation = a.id " +
                        "WHERE s.language = ? AND s.version = ?",
                    { row, _ -> read(row) },
                    scope.language,
                    scope.version,
                ).singleOrNull()
        }

    override fun confirm(
        request: ParserActivationRequest,
        operator: String,
        jobFor: (UUID, Instant) -> NewBackgroundJob,
    ): ParserActivationResult =
        transaction.commit {
            // Serialize retries of a request even if a conflicting caller supplied a different scope.
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", request.requestId.toString())
            replay(request) ?: write(request, operator, jobFor)
        }

    private fun write(
        request: ParserActivationRequest,
        operator: String,
        jobFor: (UUID, Instant) -> NewBackgroundJob,
    ): ParserActivationResult {
        val scope = request.scope
        jdbc.update(
            "INSERT INTO parser_activation_scopes (language, version) VALUES (?, ?) ON CONFLICT DO NOTHING",
            scope.language,
            scope.version,
        )
        jdbc.queryForList(
            "SELECT active_activation FROM parser_activation_scopes WHERE language = ? AND version = ? FOR UPDATE",
            scope.language,
            scope.version,
        )
        val at =
            checkNotNull(
                jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp::class.java),
            ).toInstant()
        val job = jobFor(request.requestId, at)
        jobs.insert(job)
        jdbc.update(
            "INSERT INTO parser_activations (id, language, version, processor_revision, " +
                "operator_identity, reason, job_id, activated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            request.requestId,
            scope.language,
            scope.version,
            request.processor.value,
            operator,
            request.reason,
            job.event.jobId,
            java.sql.Timestamp.from(at),
        )
        jdbc.update(
            "UPDATE parser_activation_scopes SET active_activation = ? WHERE language = ? AND version = ?",
            request.requestId,
            scope.language,
            scope.version,
        )
        return ParserActivationResult.Confirmed(
            ParserActivation(
                id = request.requestId,
                scope = scope,
                processor = request.processor,
                jobId = job.event.jobId,
                activatedAt = at,
            ),
            replayed = false,
        )
    }

    private fun read(row: ResultSet): ParserActivation =
        ParserActivation(
            id = row.getObject("id", UUID::class.java),
            scope = ParserScope(row.getString("language"), row.getString("version")),
            processor = ProcessorRevision(row.getString("processor_revision")),
            jobId = row.getObject("job_id", UUID::class.java),
            activatedAt = row.getTimestamp("activated_at").toInstant(),
        )
}
