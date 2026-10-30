package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.execution.ExecutionDescriptor
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.language.execution.LanguageExecutor
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class RunLauncher(
    private val language: Language,
    private val audit: ExecutionAudit,
) {
    fun launch(
        actorId: String,
        snapshot: ExecutionSnapshot,
    ): RunResult<AuditedRun> =
        when (val selected = language.executorFor(snapshot.language)) {
            is ExecutionResult.Rejected -> {
                RunResult.Refused(RunFailure.UNSUPPORTED_CAPABILITY)
            }

            is ExecutionResult.Failed -> {
                RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
            }

            is ExecutionResult.Success -> {
                if (snapshot.version !in selected.value.supportedVersions) {
                    RunResult.Refused(RunFailure.UNSUPPORTED_VERSION)
                } else {
                    reserveAndStart(actorId, snapshot, selected.value)
                }
            }
        }

    private fun reserveAndStart(
        actorId: String,
        snapshot: ExecutionSnapshot,
        executor: LanguageExecutor,
    ): RunResult<AuditedRun> =
        try {
            val run =
                AuditedRun(id = UUID.randomUUID(), actorId = actorId, snapshot = snapshot, startedAt = Instant.now())
            if (!audit.reserve(run)) {
                RunResult.Refused(RunFailure.ACTIVE)
            } else {
                when (val started = executor.start(snapshot.request())) {
                    is ExecutionResult.Success -> {
                        bind(run, executor, started.value)
                    }

                    is ExecutionResult.Rejected -> {
                        audit.reject(run.id, started.reason)
                        RunResult.Refused(executionFailure(started.reason))
                    }

                    is ExecutionResult.Failed -> {
                        audit.unknown(run.id, started.reason, release = !started.uncertain)
                        RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
                    }
                }
            }
        } catch (_: MetadataPersistenceException) {
            RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
        }

    private fun bind(
        run: AuditedRun,
        executor: LanguageExecutor,
        descriptor: ExecutionDescriptor,
    ): RunResult<AuditedRun> =
        try {
            audit.bind(run.id, descriptor)
            RunResult.Success(run.copy(remote = descriptor))
        } catch (_: MetadataPersistenceException) {
            // A known worker must not be abandoned when its association cannot be saved.
            executor.cancel(descriptor.id)
            RunResult.Refused(RunFailure.DEPENDENCY_UNAVAILABLE)
        }
}
