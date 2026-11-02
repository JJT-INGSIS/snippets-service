package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.background.ConfirmedJobChange
import com.jjt.ingsis.snippets.background.EventType
import com.jjt.ingsis.snippets.background.ItemState
import com.jjt.ingsis.snippets.background.JobStep
import com.jjt.ingsis.snippets.background.NewJobItem
import com.jjt.ingsis.snippets.background.newJob
import com.jjt.ingsis.snippets.metadata.AtomicJobWrite
import com.jjt.ingsis.snippets.metadata.JdbcJobProgress
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.metadata.creationRequest
import com.jjt.ingsis.snippets.permissions.AccessChecker
import com.jjt.ingsis.snippets.permissions.AccessLookup
import com.jjt.ingsis.snippets.permissions.AccessRelation
import com.jjt.ingsis.snippets.permissions.SnippetAccess
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@TestConfiguration(proxyBeanMethods = false)
class JobProgressHttpConfiguration {
    @Bean
    @Primary
    fun jobProgressAccess(): JobProgressAccess = JobProgressAccess()
}

class JobProgressAccess : AccessChecker {
    val grants = ConcurrentHashMap<Pair<UUID, String>, AccessLookup>()

    override fun check(
        snippetId: UUID,
        actorId: String,
    ): AccessLookup = grants[snippetId to actorId] ?: AccessLookup.Denied
}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["execution.recovery.delay-ms=3600000"],
)
@ActiveProfiles("dev")
@Import(MetadataTestConfiguration::class, JobProgressHttpConfiguration::class)
class JobProgressHttpTest(
    @Autowired private val atomic: AtomicJobWrite,
    @Autowired private val jobs: JdbcJobProgress,
    @Autowired private val snippets: SnippetMetadataStore,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val access: JobProgressAccess,
    @Value("\${local.server.port}") private val port: Int,
) {
    private val client = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `progress uses current owner permission and never grants access from a job id`() {
        val owner = UUID.randomUUID().toString()
        val reader = UUID.randomUUID().toString()
        val snippet = snippet()
        val job = save(EventType.SnippetUpdated, mapOf("snippetId" to snippet.toString()))
        access.grants[snippet to owner] = AccessLookup.Granted(SnippetAccess(snippet, owner, AccessRelation.OWNED))
        access.grants[snippet to reader] = AccessLookup.Granted(SnippetAccess(snippet, owner, AccessRelation.SHARED))
        assertThat(get(job, null).statusCode()).isEqualTo(401)
        assertThat(get(job, UUID.randomUUID().toString()).statusCode()).isEqualTo(404)
        assertThat(get(job, reader).statusCode()).isEqualTo(404)
        assertThat(get(job, owner).statusCode()).isEqualTo(200)
        access.grants.remove(snippet to owner)
        assertThat(get(job, owner).statusCode()).isEqualTo(404)
    }

    @Test
    fun `personal rules are private and global parser remains denied even to its initiator`() {
        val owner = UUID.randomUUID().toString()
        val rules = save(EventType.FormattingRulesChanged, mapOf("ownerId" to owner))
        assertThat(get(rules, owner).statusCode()).isEqualTo(200)
        assertThat(get(rules, "another-owner").statusCode()).isEqualTo(404)
        val parser = save(EventType.ParserVersionActivated, mapOf("ownerId" to owner))
        assertThat(get(parser, owner).statusCode()).isEqualTo(404)
    }

    @Test
    fun `DTO exposes discovered counts and selectionComplete without internal or sensitive fields`() {
        val owner = UUID.randomUUID().toString()
        val id = save(EventType.LintRulesChanged, mapOf("ownerId" to owner))
        val lease = checkNotNull(jobs.claim(id)?.lease)
        jobs.select(
            lease = lease,
            items =
                List(2) {
                    NewJobItem(
                        id = UUID.randomUUID(),
                        jobId = id,
                        key = "item-$it",
                        revisions = mapOf("rules" to "v1"),
                    )
                },
            cursor = "next-page",
            complete = false,
        )
        val item = jobs.items(lease).single()
        jobs.completeItem(
            lease = lease,
            itemId = item.id,
            result = "sensitive-result-reference",
            state = ItemState.COMPLETED,
        )
        jobs.finish(lease, JobStep.Yield)
        val response = get(id, owner)
        assertThat(response.statusCode()).isEqualTo(200)
        val body = json.readTree(response.body())
        assertThat(body.get("processed").asLong()).isEqualTo(1)
        assertThat(body.get("failed").asLong()).isZero()
        assertThat(body.get("pending").asLong()).isEqualTo(1)
        assertThat(body.get("selectionComplete").asBoolean()).isFalse()
        assertThat(
            body.properties().map {
                it.key
            },
        ).containsExactlyInAnyOrder("id", "status", "processed", "failed", "pending", "selectionComplete")
        assertThat(response.body()).doesNotContain("sensitive-result", "lease", "ownerId", "cursor", "code", "token")
    }

    @Test
    fun `permission dependency failure is not reported as permission denial`() {
        val owner = UUID.randomUUID().toString()
        val snippet = snippet()
        val job = save(EventType.SnippetUpdated, mapOf("snippetId" to snippet.toString()))
        access.grants[snippet to owner] =
            AccessLookup.Failed(com.jjt.ingsis.snippets.permissions.PermissionsFailure.UNAVAILABLE)
        assertThat(get(job, owner).statusCode()).isEqualTo(503)
    }

    private fun save(
        type: EventType,
        scope: Map<String, String>,
    ): UUID {
        val job = newJob().let { it.copy(event = it.event.copy(eventType = type, scope = scope)) }
        return atomic.commit { ConfirmedJobChange(job.event.jobId, job) }
    }

    private fun snippet(): UUID {
        val request = creationRequest()
        snippets.prepareCreation(request)
        val id =
            checkNotNull(
                jdbc.queryForObject("SELECT id FROM snippets WHERE creation_key = ?", UUID::class.java, request.key),
            )
        snippets.confirmCreation(id)
        return id
    }

    private fun get(
        id: UUID,
        actor: String?,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/jobs/$id")).GET()
        if (actor != null) request.header("X-Dev-Actor-Id", actor)
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
