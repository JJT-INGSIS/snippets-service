package com.jjt.ingsis.snippets.background

import com.jjt.ingsis.snippets.metadata.AtomicJobWrite
import com.jjt.ingsis.snippets.metadata.JdbcExecutionCapacity
import com.jjt.ingsis.snippets.metadata.JdbcJobProgress
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["snippets.capacity.enabled=true", "snippets.worker.lease=3s", "execution.recovery.delay-ms=3600000"],
)
@Import(MetadataTestConfiguration::class)
class JobSupersessionIntegrationTest(
    @Autowired private val jobs: JdbcJobProgress,
    @Autowired private val atomic: AtomicJobWrite,
    @Autowired private val capacity: JdbcExecutionCapacity,
    @Autowired private val jdbc: JdbcTemplate,
) {
    @BeforeEach
    fun freeBackgroundCapacity() {
        jdbc.update("DELETE FROM execution_permits WHERE kind = 'BACKGROUND'")
    }

    @Test
    fun `a superseded job ends terminal and supersedes only its open items`() {
        val work = claimedWith(items = 3)
        val finished = work.items().single()
        work.complete(finished, "derived-blob")

        assertThat(jobs.finish(work.lease, JobStep.Superseded)).isTrue()

        assertThat(stateOf(work.lease.jobId)).isEqualTo("SUPERSEDED")
        assertThat(itemStates(work.lease.jobId))
            .containsEntry(finished.id, "COMPLETED")
            .containsValues("SUPERSEDED", "SUPERSEDED")
        assertThat(jobs.progress(work.lease.jobId))
            .extracting("status", "processed", "failed", "pending")
            .containsExactly(JobState.SUPERSEDED, 3L, 0L, 0L)
    }

    @Test
    fun `a superseded step with a held permit stays uncertain until it is reconciled`() {
        val work = claimedWith(items = 1)
        val item = work.items().single()
        checkNotNull(work.beginRemote(item))

        assertThat(jobs.finish(work.lease, JobStep.Superseded)).isTrue()

        assertThat(stateOf(work.lease.jobId)).isEqualTo("UNKNOWN")
        assertThat(itemStates(work.lease.jobId)).containsEntry(item.id, "UNKNOWN")
    }

    @Test
    fun `a failed item keeps its reason`() {
        val work = claimedWith(items = 1)
        val item = work.items().single()

        work.complete(item = item, reference = null, state = ItemState.FAILED, reason = "UNFORMATTABLE")

        assertThat(reasonOf(item.id)).isEqualTo("UNFORMATTABLE")
    }

    @Test
    fun `a failed item with a permit keeps a safe reason and releases the permit`() {
        val work = claimedWith(items = 1)
        val item = work.items().single()
        val permit = checkNotNull(work.beginRemote(item))

        work.completeRemote(permit = permit.id, reference = null, state = ItemState.FAILED, reason = "not a code!")

        assertThat(reasonOf(item.id)).isEqualTo("HANDLER_FAILURE")
        assertThat(capacity.held(work.lease.jobId)).isNull()
    }

    @Test
    fun `completing an item without a reason clears any earlier one`() {
        val work = claimedWith(items = 1)
        val item = work.items().single()

        work.complete(item, "derived-blob")

        assertThat(reasonOf(item.id)).isNull()
    }

    private fun claimedWith(items: Int): JobWork {
        val job = newJob()
        atomic.commit { ConfirmedJobChange(Unit, job) }
        val work = JobWork(checkNotNull(jobs.claim(job.event.jobId)), jobs, capacity)
        val selected =
            List(items) { index ->
                NewJobItem(
                    id = UUID.randomUUID(),
                    jobId = job.event.jobId,
                    key = "snippet-$index",
                    revisions = mapOf("code" to "ref-$index"),
                )
            }
        work.select(selected, cursor = "last", complete = true)
        return work
    }

    private fun stateOf(jobId: UUID): String? =
        jdbc.queryForObject("SELECT state FROM background_jobs WHERE id = ?", String::class.java, jobId)

    private fun itemStates(jobId: UUID): Map<UUID, String> =
        jdbc
            .queryForList("SELECT id, state FROM background_job_items WHERE job_id = ?", jobId)
            .associate { row -> row.getValue("id") as UUID to row.getValue("state") as String }

    private fun reasonOf(itemId: UUID): String? =
        jdbc.queryForObject("SELECT last_error FROM background_job_items WHERE id = ?", String::class.java, itemId)
}
