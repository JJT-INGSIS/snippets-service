package com.jjt.ingsis.snippets.automatic

import com.jjt.ingsis.snippets.background.ItemState
import com.jjt.ingsis.snippets.background.JobStep
import com.jjt.ingsis.snippets.background.JobWork
import com.jjt.ingsis.snippets.background.RemoteExecution
import com.jjt.ingsis.snippets.background.RemotePermit
import com.jjt.ingsis.snippets.execution.AuditedRun
import com.jjt.ingsis.snippets.execution.RunProgress
import com.jjt.ingsis.snippets.execution.RunResult
import com.jjt.ingsis.snippets.execution.terminalStates
import com.jjt.ingsis.snippets.language.execution.ExecutionDescriptor
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.language.execution.LanguageEventStream
import com.jjt.ingsis.snippets.language.execution.LanguageExecutor
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

@Component
class AutomaticRemoteRuns(
    private val runs: AutomaticRuns,
) {
    fun start(
        work: JobWork,
        permit: RemotePermit,
        attempt: AuditedRun,
        executor: LanguageExecutor,
    ): JobStep =
        when (val started = executor.start(attempt.snapshot.request())) {
            is ExecutionResult.Success -> {
                bind(work = work, permit = permit, attempt = attempt, executor = executor, remote = started.value)
            }

            is ExecutionResult.Rejected -> {
                if (started.reason == "capacity-exhausted") {
                    work.persist {
                        runs.failed(permit.id, started.reason, terminal = true)
                        work.capacity.rejected(work.lease, permit.id, started.reason)
                    }
                    JobStep.Yield
                } else {
                    unavailable(work, permit, started.reason)
                }
            }

            is ExecutionResult.Failed -> {
                if (started.uncertain) {
                    work.persist { runs.failed(permit.id, started.reason, terminal = false) }
                    JobStep.Unknown("START_ACKNOWLEDGMENT_MISSING")
                } else {
                    work.persist {
                        runs.failed(permit.id, started.reason, terminal = true)
                        work.capacity.rejected(work.lease, permit.id, started.reason)
                    }
                    JobStep.Yield
                }
            }
        }

    fun recover(
        work: JobWork,
        permit: RemotePermit,
        attempt: AuditedRun,
        executor: LanguageExecutor,
    ): JobStep {
        if (attempt.completed != null) {
            work.persist { work.completeRemote(permit = permit.id, reference = permit.id.toString()) }
            return JobStep.Yield
        }
        val remote = attempt.remote ?: return JobStep.Unknown("START_ACKNOWLEDGMENT_MISSING")
        return when (val described = executor.describe(remote.id)) {
            is ExecutionResult.Success -> {
                if (described.value.processor != remote.processor) {
                    JobStep.Unknown("PROCESSOR_CHANGED")
                } else if (described.value.state !in terminalStates) {
                    JobStep.Unknown("REMOTE_STILL_ACTIVE")
                } else {
                    read(work = work, permit = permit, attempt = attempt, executor = executor, remote = described.value)
                }
            }

            is ExecutionResult.Rejected -> {
                if (described.reason ==
                    "execution-not-found"
                ) {
                    unavailable(work, permit, "RESULT_UNKNOWN_SESSION_EXPIRED")
                } else {
                    JobStep.Unknown("DESCRIPTOR_REJECTED")
                }
            }

            is ExecutionResult.Failed -> {
                JobStep.Unknown("DESCRIPTOR_UNAVAILABLE")
            }
        }
    }

    // Any binding failure must cancel the known worker; the original error still propagates.
    @Suppress("TooGenericExceptionCaught")
    private fun bind(
        work: JobWork,
        permit: RemotePermit,
        attempt: AuditedRun,
        executor: LanguageExecutor,
        remote: ExecutionDescriptor,
    ): JobStep {
        try {
            work.persist {
                runs.bind(permit.id, remote)
                work.bindRemote(
                    permit.id,
                    RemoteExecution(remote.id.toString(), remote.processor?.value, remote.limits["activeGlobal"]),
                )
            }
        } catch (failure: RuntimeException) {
            executor.cancel(remote.id)
            throw failure
        }
        return read(
            work = work,
            permit = permit,
            attempt = attempt.copy(remote = remote),
            executor = executor,
            remote = remote,
        )
    }

    private fun read(
        work: JobWork,
        permit: RemotePermit,
        attempt: AuditedRun,
        executor: LanguageExecutor,
        remote: ExecutionDescriptor,
    ): JobStep =
        when (val opened = executor.openEvents(remote.id, 0)) {
            is ExecutionResult.Success -> {
                opened.value.use { stream ->
                    consume(work = work, permit = permit, attempt = attempt, stream = stream)
                }
            }

            is ExecutionResult.Rejected -> {
                if (opened.reason in setOf("execution-not-found", "history-unavailable")) {
                    if (remote.state in terminalStates) {
                        unavailable(work, permit, "RESULT_UNKNOWN_HISTORY_EXPIRED")
                    } else {
                        JobStep.Unknown("HISTORY_UNAVAILABLE")
                    }
                } else {
                    JobStep.Unknown("STREAM_REJECTED")
                }
            }

            is ExecutionResult.Failed -> {
                JobStep.Unknown("STREAM_UNAVAILABLE")
            }
        }

    private fun consume(
        work: JobWork,
        permit: RemotePermit,
        attempt: AuditedRun,
        stream: LanguageEventStream,
    ): JobStep {
        val remote = checkNotNull(attempt.remote)
        if (stream.processor != remote.processor) return JobStep.Unknown("STREAM_PROCESSOR_CHANGED")
        val progress = AtomicReference<RunResult<RunProgress>>(RunResult.Success(RunProgress()))
        stream.read { event ->
            val previous = progress.get()
            if (previous is RunResult.Success) {
                progress.set(previous.value.advance(event, attempt.snapshot, remote.limits.getValue("outputBytes")))
            }
        }
        val current = progress.get()
        val completed =
            (current as? RunResult.Success)?.value?.completed
                ?: return JobStep.Unknown("COMPLETE_HISTORY_UNAVAILABLE")
        work.persist {
            runs.complete(permit.id, completed)
            work.completeRemote(permit = permit.id, reference = permit.id.toString())
        }
        return JobStep.Yield
    }

    private fun unavailable(
        work: JobWork,
        permit: RemotePermit,
        reason: String,
    ): JobStep {
        work.persist {
            runs.failed(permit.id, reason, terminal = true)
            work.completeRemote(permit = permit.id, reference = permit.id.toString(), state = ItemState.FAILED)
        }
        return JobStep.Yield
    }
}
