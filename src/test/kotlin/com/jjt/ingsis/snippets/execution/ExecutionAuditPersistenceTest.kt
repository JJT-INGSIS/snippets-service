package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.access.ReadDecision
import com.jjt.ingsis.snippets.access.ReadGate
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.execution.ExecutionEvent
import com.jjt.ingsis.snippets.language.execution.ExecutionMode
import com.jjt.ingsis.snippets.language.execution.ExecutionPayload
import com.jjt.ingsis.snippets.language.execution.ExecutionRequest
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.language.execution.ExecutionState
import com.jjt.ingsis.snippets.metadata.LinkedSnippet
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetState
import com.jjt.ingsis.snippets.permissions.AccessRelation
import com.jjt.ingsis.snippets.permissions.SnippetAccess
import com.jjt.ingsis.snippets.storage.ContentReference
import org.assertj.core.api.Assertions.assertThat
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
    properties = ["execution.recovery.delay-ms=3600000"],
)
@Import(MetadataTestConfiguration::class)
class ExecutionAuditPersistenceTest
    @Autowired
    constructor(
        private val audit: ExecutionAudit,
        private val jdbc: JdbcTemplate,
    ) {
        @Test
        fun `start snapshot binding and final output survive a fresh adapter read without per event writes`() {
            val run = storedRun()
            assertThat(audit.reserve(run)).isTrue()
            assertThat(audit.find(run.id)).isEqualTo(run.copy(startedAt = timestamp(run.startedAt)))
            val remote = remote()
            audit.bind(run.id, remote)
            val result =
                CompletedRun(
                    outputs = listOf("", "ñ", "ñ", " line\nbreak "),
                    terminal =
                        ExecutionPayload.Terminal(
                            outcome = ExecutionState.COMPLETED,
                            reason = "program-completed",
                            diagnostics = emptyList(),
                            unusedInputs = 1,
                        ),
                    comparison = Comparison.PASSED,
                    lastSeq = 6,
                )
            audit.complete(run.id, result)
            val loaded = checkNotNull(audit.find(run.id))
            assertThat(loaded.snapshot).isEqualTo(run.snapshot)
            assertThat(loaded.remote).isEqualTo(remote)
            assertThat(loaded.completed).isEqualTo(result)
            assertThat(loaded.finishedAt).isNotNull()
            assertThat(loaded.reserved).isFalse()
            // A late retry must not replace the result already fixed.
            audit.complete(run.id, result.copy(comparison = Comparison.FAILED))
            assertThat(audit.find(run.id)?.completed).isEqualTo(result)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM snippet_execution_audit WHERE id = ?",
                    Long::class.java,
                    run.id,
                ),
            ).isEqualTo(1)
        }

        @Test
        fun `concurrent starts reserve one actor atomically and a confirmed completion frees that reservation`() {
            val run = storedRun()
            val attempts = List(12) { Callable { audit.reserve(run.copy(id = UUID.randomUUID())) } }
            val results = Executors.newFixedThreadPool(8).use { pool -> pool.invokeAll(attempts).map { it.get() } }
            assertThat(results.count { it }).isEqualTo(1)
            val reserved = audit.reserved().single { it.actorId == run.actorId }
            audit.complete(
                reserved.id,
                CompletedRun(
                    outputs = emptyList(),
                    terminal =
                        ExecutionPayload.Terminal(
                            outcome = ExecutionState.CANCELLED,
                            reason = "actor-cancelled",
                            diagnostics = emptyList(),
                            unusedInputs = null,
                        ),
                    comparison = null,
                    lastSeq = 2,
                ),
            )
            assertThat(audit.reserve(run)).isTrue()
            assertThat(audit.reserved().filter { it.actorId == run.actorId }).hasSize(1)
        }

        @Test
        fun `an unknown acknowledgment retains the actor while an expired confirmed session releases it`() {
            val run = storedRun()
            audit.reserve(run)
            audit.unknown(run.id, "start-acknowledgment-missing", release = false)
            assertThat(audit.find(run.id)?.failure).isEqualTo("start-acknowledgment-missing")
            assertThat(audit.reserve(run.copy(id = UUID.randomUUID()))).isFalse()
            audit.unknown(run.id, "execution-session-unavailable-after-restart-or-expiry", release = true)
            assertThat(audit.reserve(run.copy(id = UUID.randomUUID()))).isTrue()
            assertThat(audit.find(run.id)?.snapshot?.code).isEqualTo(run.snapshot.code)
        }

        @Test
        fun `a fresh coordinator rebuilds full output before continuing the browser cursor without restarting`() {
            val (run, executor) = boundRun()
            val sessions = sessions(run, executor)
            val found = sessions.find(run.id.toString(), ActorContext()) as RunResult.Success
            val opened = sessions.open(found.value, 2) as RunResult.Success
            val delivered = mutableListOf<RunEvent>()
            assertThat(opened.value.use { it.read(delivered::add) }).isInstanceOf(RunResult.Success::class.java)
            assertThat(delivered.map { it.seq }).containsExactly(3L, 4L, 5L)
            assertThat(audit.find(run.id)?.completed?.outputs).containsExactly("", "ñ", "ñ")
            assertThat(audit.find(run.id)?.completed?.comparison).isEqualTo(Comparison.PASSED)
            assertThat(executor.requests).hasSize(1)
        }

        @Test
        fun `periodic recovery finishes a retained terminal using the persisted association`() {
            val (run, executor) = boundRun()
            RecoverTestRuns(audit, sessions(run, executor)).recover()
            assertThat(audit.find(run.id)?.completed?.comparison).isEqualTo(Comparison.PASSED)
            assertThat(audit.find(run.id)?.reserved).isFalse()
            assertThat(executor.requests).hasSize(1)
        }

        @Test
        fun `lost event history and expired sessions preserve unknown results instead of inventing assertions`() {
            val (run, executor) = boundRun()
            executor.openFailure.set("event-history-unavailable")
            RecoverTestRuns(audit, sessions(run, executor)).recover()
            val unknown = checkNotNull(audit.find(run.id))
            assertThat(unknown.completed).isNull()
            assertThat(unknown.failure).isEqualTo("execution-event-history-unavailable")
            assertThat(unknown.reserved).isFalse()
            assertThat(unknown.snapshot.code).isEqualTo(run.snapshot.code)
            val (expired, unavailable) = boundRun()
            unavailable.runs.clear()
            RecoverTestRuns(audit, sessions(expired, unavailable)).recover()
            assertThat(audit.find(expired.id)?.completed).isNull()
            assertThat(audit.find(expired.id)?.failure).contains("restart-or-expiry")
            assertThat(audit.find(expired.id)?.reserved).isFalse()
        }

        @Test
        fun `a transport failure is not evidence to free a worker and a later recovery can succeed`() {
            val (run, executor) = boundRun()
            executor.describeFailure.set(ExecutionResult.Failed("execution-service-unavailable", true))
            val recovery = RecoverTestRuns(audit, sessions(run, executor))
            recovery.recover()
            assertThat(audit.find(run.id)?.reserved).isTrue()
            assertThat(audit.find(run.id)?.completed).isNull()
            assertThat(audit.find(run.id)?.failure).isEqualTo("recovery-execution-service-unavailable")
            executor.describeFailure.set(null)
            recovery.recover()
            assertThat(audit.find(run.id)?.completed?.comparison).isEqualTo(Comparison.PASSED)
        }

        @Test
        fun `a failed final write cannot deliver a success terminal or lose the actor reservation`() {
            val (run, executor) = boundRun()
            val unavailable =
                object : ExecutionAudit by audit {
                    override fun complete(
                        id: UUID,
                        result: CompletedRun,
                    ): Unit = throw MetadataPersistenceException(java.io.IOException("Audit unavailable"))
                }
            val channel = RunChannel(run, executor, unavailable)
            val opened = channel.open(0) as RunResult.Success
            val received = mutableListOf<RunEvent>()
            assertThat(opened.value.use { it.read(received::add) }).isInstanceOf(RunResult.Refused::class.java)
            assertThat(received.none { it.payload is ExecutionPayload.Terminal }).isTrue()
            assertThat(audit.find(run.id)?.reserved).isTrue()
            assertThat(audit.find(run.id)?.completed).isNull()
        }

        @Test
        fun `a durable terminal remains recoverable after the interpreter loses its transient session`() {
            val (run, executor) = boundRun()
            val first = sessions(run, executor)
            val channel = (first.find(run.id.toString(), ActorContext()) as RunResult.Success).value
            val opened = first.open(channel, 0) as RunResult.Success
            opened.value.use { it.read {} }
            executor.runs.clear()
            val restarted = sessions(run, executor)
            val restored = (restarted.find(run.id.toString(), ActorContext()) as RunResult.Success).value
            val described = restarted.describe(restored) as RunResult.Success
            assertThat(described.value.state).isEqualTo(ExecutionState.COMPLETED)
            val tail = restarted.open(restored, described.value.lastSeq - 1) as RunResult.Success
            val terminal = mutableListOf<RunEvent>()
            tail.value.use { it.read(terminal::add) }
            assertThat(terminal).hasSize(1)
            assertThat(terminal.single().comparison).isEqualTo(Comparison.PASSED)
            assertThat(restarted.open(restored, 0)).isEqualTo(RunResult.Refused(RunFailure.HISTORY_UNAVAILABLE))
        }

        @Test
        fun `corrupt stored audit data is a technical failure and never an empty or successful execution`() {
            val run = storedRun()
            audit.reserve(run)
            jdbc.update("UPDATE snippet_execution_audit SET snapshot = '{}' WHERE id = ?", run.id)
            org.junit.jupiter.api.Assertions.assertThrows(
                MetadataPersistenceException::class.java,
            ) { audit.find(run.id) }
            val read = sessions(run, ControlledExecutor()).find(run.id.toString(), ActorContext())
            assertThat(read).isEqualTo(RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE))
            jdbc.update("DELETE FROM snippet_execution_audit WHERE id = ?", run.id)
        }

        private fun boundRun(): Pair<AuditedRun, ControlledExecutor> {
            val run = storedRun().copy(startedAt = Instant.now().minusSeconds(30))
            val executor = ControlledExecutor()
            val descriptor =
                (
                    executor.start(
                        ExecutionRequest(
                            code = run.snapshot.code,
                            version = "1.1",
                            mode = ExecutionMode.TEST,
                            inputs = checkNotNull(run.snapshot.test).definition.inputs,
                        ),
                    ) as ExecutionResult.Success
                ).value
            audit.reserve(run)
            audit.bind(run.id, descriptor)
            return run.copy(remote = descriptor) to executor
        }

        private fun sessions(
            run: AuditedRun,
            executor: ControlledExecutor,
        ): RunSessions {
            val snapshot = run.snapshot
            val linked =
                LinkedSnippet(
                    SnippetMetadata(
                        id = snapshot.snippetId,
                        name = "stored",
                        description = null,
                        language = snapshot.language,
                        version = snapshot.version,
                        state = SnippetState.CONFIRMED,
                    ),
                    ContentReference(snapshot.contentReference),
                )
            val access = SnippetAccess(snapshot.snippetId, run.actorId, AccessRelation.OWNED)
            return RunSessions(
                identity = ActorIdentityResolver { ActorIdentity.Identified(run.actorId) },
                gate =
                    ReadGate {
                        _,
                        _,
                        ->
                        ReadDecision.Granted(linked, access)
                    },
                audit = audit,
                language = Language(emptyList(), listOf(executor)),
            )
        }

        private fun storedRun(): AuditedRun {
            val snapshot = snapshot()
            jdbc.update(
                "INSERT INTO snippets (id, name, language, version, state, creation_key, " +
                    "creation_actor_id, creation_fingerprint) " +
                    "VALUES (?, 'audit', 'printscript', '1.1', 'CONFIRMED', ?, 'owner', 'f')",
                snapshot.snippetId,
                UUID.randomUUID(),
            )
            jdbc.update(
                "INSERT INTO snippet_test_cases (id, snippet_id, inputs, expected_outputs) VALUES (?, ?, '[]', '[]')",
                checkNotNull(snapshot.test).id,
                snapshot.snippetId,
            )
            return AuditedRun(
                id = UUID.randomUUID(),
                actorId = "actor-${UUID.randomUUID()}",
                snapshot = snapshot,
                startedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
            )
        }

        private fun timestamp(value: Instant): Instant = value.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
    }
