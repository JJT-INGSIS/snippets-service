package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ExecutionRecoveryConfiguration

@Component
class RecoverTestRuns(
    private val audit: ExecutionAudit,
    private val sessions: RunSessions,
) {
    private val logger = LoggerFactory.getLogger(RecoverTestRuns::class.java)

    @Scheduled(
        fixedDelayString = "\${execution.recovery.delay-ms:5000}",
        initialDelayString = "\${execution.recovery.delay-ms:5000}",
    )
    fun recover() {
        try {
            val now = Instant.now()
            sessions.maintain(now)
            audit.reserved().filter { eligible(it, now) }.forEach(::recoverOne)
        } catch (_: MetadataPersistenceException) {
            logger.warn("Execution audit unavailable; recovery will be retried")
        }
    }

    private fun eligible(
        run: AuditedRun,
        now: Instant,
    ): Boolean =
        now.isAfter(
            run.startedAt.plusSeconds(run.remote?.limits?.getValue("reconnectSeconds") ?: INITIAL_RECONNECT_SECONDS),
        )

    private fun recoverOne(run: AuditedRun) {
        when (val found = sessions.channel(run)) {
            is RunResult.Refused -> {
                if (run.remote == null &&
                    run.failure == null
                ) {
                    audit.unknown(run.id, "start-acknowledgment-missing", release = false)
                }
            }

            is RunResult.Success -> {
                if (!found.value.connected()) recoverTerminal(found.value, run.failure)
            }
        }
    }

    private fun recoverTerminal(
        channel: RunChannel,
        previousFailure: String?,
    ) {
        when (val described = channel.executor.describe(checkNotNull(channel.run.remote).id)) {
            is ExecutionResult.Success -> {
                if (described.value.state in terminalStates) replayTerminal(channel, previousFailure)
            }

            is ExecutionResult.Rejected -> {
                if (described.reason == "execution-not-found") {
                    audit.unknown(
                        channel.run.id,
                        "execution-session-unavailable-after-restart-or-expiry",
                        release = true,
                    )
                }
            }

            is ExecutionResult.Failed -> {
                pending(channel, previousFailure, "recovery-${described.reason}")
                // Never infer a stopped worker from network failure.
            }
        }
    }

    private fun replayTerminal(
        channel: RunChannel,
        previousFailure: String?,
    ) {
        when (val opened = channel.open(channel.progress().lastSeq)) {
            is RunResult.Success -> {
                val result = opened.value.use { it.read {} }
                if (result is RunResult.Refused && result.failure == RunFailure.HISTORY_UNAVAILABLE) {
                    audit.unknown(
                        channel.run.id,
                        "execution-result-recovery-incomplete",
                        release = true,
                    )
                } else if (result is RunResult.Refused) {
                    pending(channel, previousFailure, "execution-result-recovery-pending")
                }
            }

            is RunResult.Refused -> {
                if (opened.failure in setOf(RunFailure.HISTORY_UNAVAILABLE, RunFailure.EXECUTION_MISSING)) {
                    audit.unknown(channel.run.id, "execution-event-history-unavailable", release = true)
                } else if (opened.failure != RunFailure.CONSUMER_CONFLICT) {
                    pending(channel, previousFailure, "execution-result-recovery-pending")
                }
            }
        }
    }

    private fun pending(
        channel: RunChannel,
        previous: String?,
        reason: String,
    ) {
        if (previous != reason) audit.unknown(channel.run.id, reason, release = false)
    }

    companion object {
        private const val INITIAL_RECONNECT_SECONDS = 15L
    }
}
