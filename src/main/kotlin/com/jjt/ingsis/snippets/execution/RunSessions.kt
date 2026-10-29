package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.access.ReadDecision
import com.jjt.ingsis.snippets.access.ReadGate
import com.jjt.ingsis.snippets.creation.canonicalUuid
import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.execution.ExecutionDescriptor
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class RunSessions(
    private val identity: ActorIdentityResolver,
    private val gate: ReadGate,
    private val audit: ExecutionAudit,
    private val language: Language,
) {
    private val channels = ConcurrentHashMap<UUID, RunChannel>()

    fun find(
        id: String,
        context: ActorContext,
    ): RunResult<RunChannel> {
        val actor =
            identity.resolve(context) as? ActorIdentity.Identified
                ?: return RunResult.Refused(RunFailure.IDENTITY_REQUIRED)
        val uuid = canonicalUuid(id) ?: return RunResult.Refused(RunFailure.INVALID_REQUEST)
        return try {
            val run = audit.find(uuid) ?: return RunResult.Refused(RunFailure.EXECUTION_MISSING)
            if (run.actorId != actor.actorId) return RunResult.Refused(RunFailure.ACTOR_MISMATCH)
            when (val granted = gate.open(run.snapshot.snippetId.toString(), context)) {
                is ReadDecision.Refused -> RunResult.Refused(granted.failure.toRunFailure())
                is ReadDecision.Granted -> channel(run)
            }
        } catch (_: MetadataPersistenceException) {
            RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
        }
    }

    internal fun channel(run: AuditedRun): RunResult<RunChannel> {
        if (run.remote == null) return RunResult.Refused(RunFailure.DEPENDENCY_TIMEOUT)
        if (run.finishedAt?.let {
                Instant.now().isAfter(it.plusSeconds(run.remote.limits.getValue("terminalRetentionSeconds")))
            } ==
            true
        ) {
            channels.remove(run.id)
            return RunResult.Refused(RunFailure.EXECUTION_MISSING)
        }
        if (run.failure != null && run.completed == null &&
            !run.reserved
        ) {
            return RunResult.Refused(RunFailure.HISTORY_UNAVAILABLE)
        }
        return when (val selected = language.executorFor(run.snapshot.language)) {
            is ExecutionResult.Success -> {
                RunResult.Success(
                    channels.computeIfAbsent(run.id) {
                        RunChannel(run, selected.value, audit)
                    },
                )
            }

            is ExecutionResult.Rejected -> {
                RunResult.Refused(RunFailure.UNSUPPORTED_CAPABILITY)
            }

            is ExecutionResult.Failed -> {
                RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
            }
        }
    }

    fun describe(channel: RunChannel): RunResult<ExecutionDescriptor> {
        val remote = checkNotNull(channel.run.remote)
        val completed = channel.progress().completed
        if (completed != null) {
            return RunResult.Success(remote.copy(state = completed.terminal.outcome, lastSeq = completed.lastSeq))
        }
        return when (val result = channel.executor.describe(remote.id)) {
            is ExecutionResult.Success -> {
                if (result.value.mode == remote.mode) {
                    RunResult.Success(result.value)
                } else {
                    RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
                }
            }

            is ExecutionResult.Rejected -> {
                RunResult.Refused(executionFailure(result.reason))
            }

            is ExecutionResult.Failed -> {
                RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
            }
        }
    }

    fun open(
        channel: RunChannel,
        after: Long,
    ): RunResult<RunEventStream> =
        when (val descriptor = describe(channel)) {
            is RunResult.Refused -> {
                descriptor
            }

            is RunResult.Success -> {
                if (after < 0 ||
                    after > descriptor.value.lastSeq
                ) {
                    RunResult.Refused(RunFailure.INVALID_CURSOR)
                } else {
                    channel.open(after)
                }
            }
        }

    internal fun maintain(now: Instant) {
        channels.forEach { (id, channel) ->
            channel.closeExpired(now)
            if (!channel.connected() && channel.expired(now)) channels.remove(id, channel)
        }
    }
}
