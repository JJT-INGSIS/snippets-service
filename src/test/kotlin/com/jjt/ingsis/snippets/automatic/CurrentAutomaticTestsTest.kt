package com.jjt.ingsis.snippets.automatic

import com.jjt.ingsis.snippets.access.ReadDecision
import com.jjt.ingsis.snippets.access.ReadFailure
import com.jjt.ingsis.snippets.access.ReadGate
import com.jjt.ingsis.snippets.background.JobProgress
import com.jjt.ingsis.snippets.background.JobState
import com.jjt.ingsis.snippets.execution.AuditedRun
import com.jjt.ingsis.snippets.execution.remote
import com.jjt.ingsis.snippets.execution.snapshot
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.language.execution.LanguageExecutor
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.permissions.AccessRelation
import com.jjt.ingsis.snippets.permissions.SnippetAccess
import com.jjt.ingsis.snippets.storage.ContentReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID

class CurrentAutomaticTestsTest {
    private val execution = snapshot()
    private val id = execution.snippetId
    private val processor = ProcessorRevision("sha256-v1:" + "1".repeat(64))
    private val jobId = UUID.randomUUID()
    private val language = mock(Language::class.java)
    private val executor = mock(LanguageExecutor::class.java)
    private val linked =
        LinkedSnippet(
            SnippetMetadata(
                id = id,
                name = "test",
                description = null,
                language = "printscript",
                version = "1.1",
                state = SnippetState.CONFIRMED,
            ),
            ContentReference(execution.contentReference),
        )
    private val current =
        CurrentAutomaticSnapshot(
            update =
                UpdateSnapshot(
                    jobId = jobId,
                    snippetId = id,
                    actorId = "owner",
                    language = "printscript",
                    version = "1.1",
                    reference = checkNotNull(linked.reference),
                    codeHash = execution.codeHash,
                    tests = listOf(checkNotNull(execution.test)),
                ),
            progress =
                JobProgress(
                    id = jobId,
                    status = JobState.RUNNING,
                    processed = 0,
                    failed = 0,
                    pending = 1,
                    selectionComplete = true,
                ),
            runs =
                listOf(
                    AuditedRun(
                        id = UUID.randomUUID(),
                        actorId = "owner",
                        snapshot = execution,
                        startedAt = Instant.now(),
                        remote = remote().copy(processor = processor),
                    ),
                ),
            current = true,
            items = emptyList(),
        )

    @Test
    fun `denied access never loads the projection or contacts the processor`() {
        val service =
            CurrentAutomaticTests(
                ReadGate { _, _ -> ReadDecision.Refused(ReadFailure.SNIPPET_MISSING) },
                AutomaticResults { error("Unauthorized projection") },
                language,
            )
        assertThat(service.find(id.toString(), ActorContext()))
            .isEqualTo(AutomaticLookup.Refused(ReadFailure.SNIPPET_MISSING))
    }

    @Test
    fun `both owner and authorized reader see only current test identifiers`() {
        processorAvailable()
        listOf(AccessRelation.OWNED, AccessRelation.SHARED).forEach { relation ->
            val view = view(AutomaticResults { current }, relation)
            assertThat(view.validity).isEqualTo(AutomaticValidity.CURRENT)
            assertThat(view.tests.map { it.testId }).containsExactly(execution.test!!.id)
        }
    }

    @Test
    fun `changed code or test definitions hide historical results`() {
        processorAvailable()
        val view = view(AutomaticResults { current.copy(current = false) })
        assertThat(view.validity).isEqualTo(AutomaticValidity.SUPERSEDED)
        assertThat(view.tests).isEmpty()
    }

    @Test
    fun `processor replacement invalidates the old results`() {
        processorAvailable(ProcessorRevision("sha256-v1:" + "2".repeat(64)))
        assertThat(view(AutomaticResults { current }).validity).isEqualTo(AutomaticValidity.SUPERSEDED)
        assertThat(view(AutomaticResults { current }).tests).isEmpty()
    }

    @Test
    fun `unavailable processor cannot certify old results as current`() {
        `when`(language.executorFor("printscript")).thenReturn(ExecutionResult.Failed("down"))
        val view = view(AutomaticResults { current })
        assertThat(view.validity).isEqualTo(AutomaticValidity.UNVERIFIABLE)
        assertThat(view.tests).isEmpty()
    }

    @Test
    fun `an update committed during processor lookup cannot expose the older revision`() {
        processorAvailable()
        val reads =
            java.util.concurrent.atomic
                .AtomicInteger()
        val view =
            view(
                AutomaticResults {
                    if (reads.incrementAndGet() ==
                        1
                    ) {
                        current
                    } else {
                        current.copy(update = current.update.copy(jobId = UUID.randomUUID()))
                    }
                },
            )
        assertThat(view.validity).isEqualTo(AutomaticValidity.SUPERSEDED)
        assertThat(view.tests).isEmpty()
        assertThat(view.revision).isNull()
    }

    @Test
    fun `metadata failure is explicit rather than an empty success`() {
        val result =
            service(AutomaticResults { throw MetadataPersistenceException(IllegalStateException("down")) })
                .find(id.toString(), ActorContext())
        assertThat(result).isEqualTo(AutomaticLookup.Unavailable)
    }

    private fun processorAvailable(value: ProcessorRevision = processor) {
        `when`(language.executorFor("printscript")).thenReturn(ExecutionResult.Success(executor))
        `when`(executor.processorRevision()).thenReturn(ExecutionResult.Success(value))
    }

    private fun service(
        results: AutomaticResults,
        relation: AccessRelation = AccessRelation.OWNED,
    ) = CurrentAutomaticTests(
        ReadGate { _, _ -> ReadDecision.Granted(linked, SnippetAccess(id, "owner", relation)) },
        results,
        language,
    )

    private fun view(
        results: AutomaticResults,
        relation: AccessRelation = AccessRelation.OWNED,
    ): AutomaticTestsView =
        (service(results, relation).find(id.toString(), ActorContext()) as AutomaticLookup.Found).view
}
