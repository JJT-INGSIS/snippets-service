package com.jjt.ingsis.snippets.background

import com.jjt.ingsis.snippets.metadata.AtomicJobWrite
import com.jjt.ingsis.snippets.metadata.ContentReplacementResult
import com.jjt.ingsis.snippets.metadata.JdbcEventDeliveries
import com.jjt.ingsis.snippets.metadata.JdbcJobRecords
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.metadata.MetadataTransaction
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetDetails
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.metadata.creationRequest
import com.jjt.ingsis.snippets.storage.ContentReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class)
class AtomicJobWriteTest(
    @Autowired private val atomic: AtomicJobWrite,
    @Autowired private val jobs: JdbcJobRecords,
    @Autowired private val links: SnippetContentLinks,
    @Autowired private val metadata: SnippetMetadataStore,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val transaction: MetadataTransaction,
    @Autowired private val deliveries: JdbcEventDeliveries,
) {
    @Test
    fun `commit stores replacement revision job and outbox together`() {
        val snippet = confirmedSnippet()
        val job = newJob()
        val reference = ContentReference(UUID.randomUUID().toString())
        val result =
            atomic.commit {
                val replaced = links.replaceContent(snippet, renamed(), reference)
                check(replaced is ContentReplacementResult.Replaced)
                ConfirmedJobChange(replaced, job)
            }
        assertThat(result.metadata.name).isEqualTo("Changed")
        assertThat(links.findContent(snippet)).isEqualTo(reference)
        assertThat(count("background_jobs", "id", job.event.jobId)).isEqualTo(1)
        assertThat(count("event_outbox", "event_id", job.event.eventId)).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "SELECT revisions ->> 'code' FROM background_jobs WHERE id = ?",
                String::class.java,
                job.event.jobId,
            ),
        ).isEqualTo(job.event.revisions["code"])
    }

    @Test
    fun `outbox insertion failure rolls back the actual replacement and job`() {
        val snippet = confirmedSnippet()
        val previous = links.findLinked(snippet)
        val existing = newJob()
        atomic.commit { ConfirmedJobChange(Unit, existing) }
        val conflicting =
            newJob().let {
                it.copy(
                    event = it.event.copy(eventId = existing.event.eventId),
                    reaction = "second-reaction",
                )
            }

        assertThatThrownBy {
            atomic.commit {
                check(
                    links.replaceContent(
                        snippet,
                        renamed(),
                        ContentReference("new-blob"),
                    ) is ContentReplacementResult.Replaced,
                )
                ConfirmedJobChange(Unit, conflicting)
            }
        }.isInstanceOf(RuntimeException::class.java)

        assertThat(links.findLinked(snippet)).isEqualTo(previous)
        assertThat(count("background_jobs", "id", conflicting.event.jobId)).isZero()
        assertThat(count("event_outbox", "event_id", existing.event.eventId)).isEqualTo(1)
    }

    @Test
    fun `unconfirmed change produces no job or event`() {
        val snippet = confirmedSnippet()
        val previous = links.findLinked(snippet)
        val before = jdbc.queryForObject("SELECT count(*) FROM event_outbox", Int::class.java)
        assertThatThrownBy {
            atomic.commit<Unit> {
                links.replaceContent(snippet, renamed(), ContentReference("uncommitted-blob"))
                error("Change rejected before confirmation")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(links.findLinked(snippet)).isEqualTo(previous)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox", Int::class.java)).isEqualTo(before)
    }

    @Test
    fun `job records cannot be inserted outside an explicit transaction`() {
        val job = newJob()
        assertThatThrownBy { jobs.insert(job) }.isInstanceOf(RuntimeException::class.java)
        assertThat(count("background_jobs", "id", job.event.jobId)).isZero()
    }

    @Test
    fun `publisher cannot claim an event before its transaction commits`() {
        val job = newJob()
        transaction.commit {
            jobs.insert(job)
            assertThat(deliveries.claim()?.event?.eventId).isNotEqualTo(job.event.eventId)
        }
        assertThat(count("event_outbox", "event_id", job.event.eventId)).isEqualTo(1)
    }

    @Test
    fun `oversized envelope rolls back the confirmed database change`() {
        val snippet = confirmedSnippet()
        val previous = links.findLinked(snippet)
        val job = newJob().let { it.copy(event = it.event.copy(scope = mapOf("oversized" to "x".repeat(17000)))) }
        assertThatThrownBy {
            atomic.commit {
                links.replaceContent(snippet, renamed(), ContentReference("large-event-blob"))
                ConfirmedJobChange(Unit, job)
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(links.findLinked(snippet)).isEqualTo(previous)
        assertThat(count("background_jobs", "id", job.event.jobId)).isZero()
    }

    @Test
    fun `items deduplicate by logical identity within their job`() {
        val job = newJob()
        atomic.commit { ConfirmedJobChange(Unit, job) }
        val item =
            NewJobItem(
                id = UUID.randomUUID(),
                jobId = job.event.jobId,
                key = "snippet:test:revision",
                revisions = job.event.revisions,
            )
        transaction.commit {
            assertThat(jobs.insertItem(item)).isTrue()
            assertThat(jobs.insertItem(item.copy(id = UUID.randomUUID()))).isFalse()
        }
        assertThat(count("background_job_items", "job_id", job.event.jobId)).isEqualTo(1)
    }

    private fun confirmedSnippet(): UUID {
        val request = creationRequest()
        metadata.prepareCreation(request)
        val id = jdbc.queryForObject("SELECT id FROM snippets WHERE creation_key = ?", UUID::class.java, request.key)
        return checkNotNull(metadata.confirmCreation(checkNotNull(id))).id
    }

    private fun renamed(): SnippetDetails =
        SnippetDetails(name = "Changed", description = null, language = "printscript", version = "1.0")

    private fun count(
        table: String,
        column: String,
        id: UUID,
    ): Int = checkNotNull(jdbc.queryForObject("SELECT count(*) FROM $table WHERE $column = ?", Int::class.java, id))
}
