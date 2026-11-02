package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.automatic.SnippetUpdateCommit
import com.jjt.ingsis.snippets.background.DomainEvent
import com.jjt.ingsis.snippets.background.EventType
import com.jjt.ingsis.snippets.background.NewBackgroundJob
import com.jjt.ingsis.snippets.execution.SnapshotFingerprint
import com.jjt.ingsis.snippets.execution.TestSnapshot
import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.testcase.TestCaseStore
import com.jjt.ingsis.snippets.update.UpdateCandidate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

@Repository
class JdbcSnippetUpdateCommit(
    private val transaction: MetadataTransaction,
    private val links: SnippetContentLinks,
    private val tests: TestCaseStore,
    private val fingerprints: SnapshotFingerprint,
    private val jobs: JdbcJobRecords,
    private val jdbc: JdbcTemplate,
) : SnippetUpdateCommit {
    private val json = JsonMapper.builder().findAndAddModules().build()

    override fun commit(
        candidate: UpdateCandidate,
        reference: ContentReference,
    ): ContentReplacementResult =
        transaction.commit {
            val result = links.replaceContent(candidate.snippetId, candidate.replacement.details(), reference)
            if (result is ContentReplacementResult.Replaced) record(candidate, reference)
            result
        }

    private fun record(
        candidate: UpdateCandidate,
        reference: ContentReference,
    ) {
        val draft = candidate.replacement
        val jobId = UUID.randomUUID()
        val selected =
            tests.list(candidate.snippetId).map {
                TestSnapshot(it.id, it.definition, fingerprints.test(it.definition))
            }
        val codeHash = fingerprints.code(draft.language, draft.version, draft.code)
        val event =
            DomainEvent(
                eventId = UUID.randomUUID(),
                jobId = jobId,
                eventType = EventType.SnippetUpdated,
                correlationId = jobId,
                occurredAt = Instant.now(),
                scope = mapOf("snippetId" to candidate.snippetId.toString(), "ownerId" to candidate.actorId),
                revisions = mapOf("update" to jobId.toString(), "code" to codeHash),
            )
        jobs.insert(NewBackgroundJob(event = event, reaction = "automatic-tests", initiatedBy = candidate.actorId))
        jdbc.update(
            "INSERT INTO snippet_update_snapshots (job_id, snippet_id, actor_id, language, version, " +
                "content_reference, code_hash, tests) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))",
            jobId,
            candidate.snippetId,
            candidate.actorId,
            draft.language,
            draft.version,
            reference.value,
            codeHash,
            json.writeValueAsString(selected),
        )
    }
}
