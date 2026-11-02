package com.jjt.ingsis.snippets.background

import com.jjt.ingsis.snippets.execution.AuditReservation
import com.jjt.ingsis.snippets.execution.AuditedRun
import com.jjt.ingsis.snippets.execution.ExecutionSnapshot
import com.jjt.ingsis.snippets.language.execution.ExecutionDescriptor
import com.jjt.ingsis.snippets.language.execution.ExecutionMode
import com.jjt.ingsis.snippets.language.execution.ExecutionState
import com.jjt.ingsis.snippets.metadata.AtomicJobWrite
import com.jjt.ingsis.snippets.metadata.JdbcExecutionAudit
import com.jjt.ingsis.snippets.metadata.JdbcExecutionCapacity
import com.jjt.ingsis.snippets.metadata.JdbcJobProgress
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.metadata.MetadataTransaction
import com.jjt.ingsis.snippets.metadata.ResolveManualExecution
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.metadata.creationRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["snippets.capacity.enabled=true", "execution.recovery.delay-ms=3600000"],
)
@Import(MetadataTestConfiguration::class)
class ExecutionCapacityIntegrationTest(
    @Autowired private val capacity: JdbcExecutionCapacity,
    @Autowired private val audit: JdbcExecutionAudit,
    @Autowired private val jobs: JdbcJobProgress,
    @Autowired private val atomic: AtomicJobWrite,
    @Autowired private val snippets: SnippetMetadataStore,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val transaction: MetadataTransaction,
    @Autowired private val resolver: ResolveManualExecution,
) {
    @BeforeEach
    fun reset() {
        jdbc.update("DELETE FROM execution_permits")
        jdbc.update("DELETE FROM snippet_execution_audit")
        jdbc.update("DELETE FROM event_outbox")
        jdbc.update("DELETE FROM background_job_items")
        jdbc.update("DELETE FROM background_jobs")
    }

    @Test
    fun `background uses at most one slot while manual executions share the remaining budget`() {
        val (lease, item) = background()
        assertThat(capacity.begin(lease, item.id)).isNotNull()
        val (secondLease, secondItem) = background()
        assertThat(capacity.begin(secondLease, secondItem.id)).isNull()
        val runs = List(4) { manual() }
        assertThat(runs.take(3).map { audit.reserveForStart(it) }).containsOnly(AuditReservation.RESERVED)
        assertThat(audit.reserveForStart(runs.last())).isEqualTo(AuditReservation.CAPACITY)
        assertThat(held()).isEqualTo(4)
    }

    @Test
    fun `disabled admission still releases an existing permit adopted by the migration`() {
        jdbc.update("UPDATE execution_capacity SET enabled = FALSE WHERE id = 1")
        val disabledCapacity =
            JdbcExecutionCapacity(
                jdbc = jdbc,
                transaction = transaction,
                jobs = jobs,
                settings = CapacitySettings(enabled = false),
            )
        val disabledAudit = JdbcExecutionAudit(jdbc, transaction, disabledCapacity)
        val run = manual()
        assertThat(disabledAudit.reserveForStart(run)).isEqualTo(AuditReservation.RESERVED)
        jdbc.update(
            "INSERT INTO execution_permits (id, kind, manual_run_id, state) VALUES (?, 'MANUAL', ?, 'UNKNOWN')",
            run.id,
            run.id,
        )
        assertThat(held()).isEqualTo(1)
        disabledAudit.reject(run.id, "START_REJECTED")
        assertThat(held()).isZero()
    }

    @Test
    fun `manual reserve prevents background from consuming the last available slot`() {
        repeat(3) { assertThat(audit.reserveForStart(manual())).isEqualTo(AuditReservation.RESERVED) }
        val (lease, item) = background()
        assertThat(capacity.begin(lease, item.id)).isNull()
        assertThat(audit.reserveForStart(manual())).isEqualTo(AuditReservation.RESERVED)
    }

    @Test
    fun `overlapping coordinator instances cannot exceed the same durable budget`() {
        val anotherCapacity =
            JdbcExecutionCapacity(
                jdbc = jdbc,
                transaction = transaction,
                jobs = jobs,
                settings = CapacitySettings(enabled = false),
            )
        val anotherAudit = JdbcExecutionAudit(jdbc, transaction, anotherCapacity)
        val runs = List(8) { manual() }
        // Enable once in PostgreSQL; a replacement with a local flag off still honors the durable gate.
        transaction.commit { capacity.lock() }
        val results =
            Executors.newFixedThreadPool(8).use { pool ->
                pool
                    .invokeAll(
                        runs.mapIndexed { index, run ->
                            Callable {
                                (if (index % 2 == 0) audit else anotherAudit).reserveForStart(run)
                            }
                        },
                    ).map { it.get() }
            }
        assertThat(results.count { it == AuditReservation.RESERVED }).isEqualTo(4)
        assertThat(results.count { it == AuditReservation.CAPACITY }).isEqualTo(4)
        assertThat(held()).isEqualTo(4)
    }

    @Test
    fun `known but uncertain manual execution keeps its slot until termination evidence exists`() {
        val run = manual()
        assertThat(audit.reserveForStart(run)).isEqualTo(AuditReservation.RESERVED)
        audit.bind(run.id, descriptor())
        audit.unknown(run.id, "execution-session-unavailable-after-restart-or-expiry", release = true)
        assertThat(audit.find(run.id)?.reserved).isTrue()
        assertThat(held()).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT state FROM execution_permits WHERE id = ?", String::class.java, run.id))
            .isEqualTo("UNKNOWN")
        assertThatThrownBy { resolver.resolve(run.id) { null } }.isInstanceOf(IllegalStateException::class.java)
        assertThat(held()).isEqualTo(1)
        resolver.resolve(run.id) { "INCIDENT_123_PROVIDER_STOP_VERIFIED" }
        assertThat(audit.find(run.id)?.reserved).isFalse()
        assertThat(held()).isZero()
    }

    @Test
    fun `expired job lease never releases the remote permit or permits another start`() {
        val (lease, item) = background()
        val permit = checkNotNull(capacity.begin(lease, item.id))
        capacity.bind(lease, permit.id, RemoteExecution("remote-session", "processor-build", activeLimit = 4L))
        jdbc.update("UPDATE background_jobs SET lease_until = clock_timestamp() - INTERVAL '1 second'")
        val replacement = checkNotNull(jobs.claim(lease.jobId)?.lease)
        assertThatThrownBy {
            capacity.complete(lease = lease, permitId = permit.id, result = "late", state = ItemState.COMPLETED)
        }.isInstanceOf(LostJobLease::class.java)
        assertThat(capacity.held(lease.jobId)?.remote?.id).isEqualTo("remote-session")
        assertThat(capacity.begin(replacement, item.id)).isNull()
        capacity.complete(
            lease = replacement,
            permitId = permit.id,
            result = "verified-result",
            state = ItemState.COMPLETED,
        )
        assertThat(held()).isZero()
        assertThat(jobs.progress(lease.jobId)?.processed).isEqualTo(1)
    }

    @Test
    fun `termination evidence is rejected when the remote association changes during verification`() {
        val (lease, item) = background()
        val permit = checkNotNull(capacity.begin(lease, item.id))
        assertThatThrownBy {
            capacity.resolve(
                lease,
                permit.id,
                RemoteTerminationEvidence {
                    capacity.bind(
                        lease,
                        permit.id,
                        RemoteExecution("late-session", "processor-build", activeLimit = 4L),
                    )
                    "NO_SESSION_OBSERVED_BEFORE_BIND"
                },
            )
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(held()).isEqualTo(1)
        assertThatThrownBy { capacity.rejected(lease, permit.id, "NOT_REALLY_REJECTED") }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(held()).isEqualTo(1)
    }

    @Test
    fun `background capacity mismatch retains its permit until termination is proven`() {
        val (lease, item) = background()
        val permit = checkNotNull(capacity.begin(lease, item.id))
        assertThatThrownBy {
            capacity.bind(lease, permit.id, RemoteExecution("remote-session", "processor-build", activeLimit = 2L))
        }.isInstanceOf(RuntimeException::class.java)
        assertThat(held()).isEqualTo(1)
        capacity.resolve(lease, permit.id, RemoteTerminationEvidence { "PROCESSOR_STOP_VERIFIED" })
        assertThat(held()).isZero()
        assertThat(jobs.progress(lease.jobId)?.failed).isEqualTo(1)
    }

    @Test
    fun `a rejected start releases capacity while a declared capacity mismatch fails closed`() {
        val run = manual()
        audit.reserveForStart(run)
        assertThatThrownBy { audit.bind(run.id, descriptor().copy(limits = mapOf("activeGlobal" to 9L))) }
            .isInstanceOf(RuntimeException::class.java)
        assertThat(held()).isEqualTo(1)
        resolver.resolve(run.id) { "PROCESSOR_STOP_VERIFIED" }
        val rejected = manual()
        audit.reserveForStart(rejected)
        assertThat(held()).isEqualTo(1)
        audit.reject(rejected.id, "execution-capacity-exceeded")
        assertThat(held()).isZero()
    }

    private fun background(): Pair<JobLease, WorkItem> {
        val job = newJob()
        atomic.commit { ConfirmedJobChange(Unit, job) }
        val lease = checkNotNull(jobs.claim(job.event.jobId)?.lease)
        jobs.select(
            lease = lease,
            items =
                listOf(
                    NewJobItem(
                        id = UUID.randomUUID(),
                        jobId = lease.jobId,
                        key = "test",
                        revisions = job.event.revisions,
                    ),
                ),
            cursor = null,
            complete = true,
        )
        return lease to jobs.items(lease).single()
    }

    private fun manual(): AuditedRun {
        val request = creationRequest()
        snippets.prepareCreation(request)
        val id =
            checkNotNull(
                jdbc.queryForObject("SELECT id FROM snippets WHERE creation_key = ?", UUID::class.java, request.key),
            )
        snippets.confirmCreation(id)
        return AuditedRun(
            id = UUID.randomUUID(),
            actorId = UUID.randomUUID().toString(),
            snapshot =
                ExecutionSnapshot(
                    snippetId = id,
                    language = "printscript",
                    version = "1.0",
                    code = "println(1);",
                    contentReference = "fixture",
                    codeHash = "fixture-hash",
                    test = null,
                ),
            startedAt = Instant.now(),
        )
    }

    private fun descriptor(): ExecutionDescriptor =
        ExecutionDescriptor(
            id = UUID.randomUUID(),
            mode = ExecutionMode.INTERACTIVE,
            state = ExecutionState.RUNNING,
            limits = mapOf("activeGlobal" to 4L),
            lastSeq = 0,
        )

    private fun held(): Int =
        checkNotNull(
            jdbc.queryForObject("SELECT count(*) FROM execution_permits WHERE state <> 'RELEASED'", Int::class.java),
        )
}
