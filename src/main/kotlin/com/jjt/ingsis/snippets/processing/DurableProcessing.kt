package com.jjt.ingsis.snippets.processing

import com.jjt.ingsis.snippets.background.CapacitySettings
import com.jjt.ingsis.snippets.background.ItemState
import com.jjt.ingsis.snippets.background.JobStep
import com.jjt.ingsis.snippets.background.JobWork
import com.jjt.ingsis.snippets.background.RemoteExecution
import com.jjt.ingsis.snippets.background.RemotePermit
import com.jjt.ingsis.snippets.background.RemoteTerminationEvidence
import com.jjt.ingsis.snippets.background.WorkItem
import com.jjt.ingsis.snippets.language.operations.LanguageOperations
import com.jjt.ingsis.snippets.language.operations.ProcessingKind
import com.jjt.ingsis.snippets.language.operations.ProcessingOperation
import com.jjt.ingsis.snippets.language.operations.ProcessingReply
import com.jjt.ingsis.snippets.language.operations.ProcessingState
import org.springframework.stereotype.Component
import java.util.UUID

private const val POLL_ATTEMPTS = 20
private const val POLL_DELAY_MS = 50L

@Component
class DurableProcessing(
    private val remote: LanguageOperations,
    reactions: List<ProcessingReaction>,
    private val settings: CapacitySettings,
) {
    private val reactions = reactions.associateBy { it.kind }

    init {
        require(this.reactions.size == reactions.size)
    }

    fun start(
        work: JobWork,
        item: WorkItem,
        kind: ProcessingKind,
    ): JobStep {
        val reaction = reactions.getValue(kind)
        return when (val preparation = reaction.prepare(work, item)) {
            is OperationPreparation.Ready -> {
                begin(work = work, item = item, reaction = reaction, prepared = preparation)
            }

            is OperationPreparation.Retry -> {
                work.retry(item, preparation.reason)
                JobStep.Yield
            }

            is OperationPreparation.Failed -> {
                work.complete(item = item, reference = null, state = ItemState.FAILED, reason = preparation.reason)
                JobStep.Complete
            }

            OperationPreparation.Superseded -> {
                work.complete(item = item, reference = null, state = ItemState.SUPERSEDED)
                JobStep.Complete
            }
        }
    }

    private fun begin(
        work: JobWork,
        item: WorkItem,
        reaction: ProcessingReaction,
        prepared: OperationPreparation.Ready,
    ): JobStep {
        val permit =
            work
                .persist {
                    val reserved = work.beginRemote(item)
                    if (reserved == null) {
                        emptyList()
                    } else {
                        val association =
                            RemoteExecution(
                                id = reserved.id.toString(),
                                processor = prepared.command.processorRevision,
                                activeLimit = settings.activeLimit.toLong(),
                                context =
                                    prepared.context +
                                        mapOf("kind" to reaction.kind.name, "version" to prepared.command.version),
                            )
                        work.bindRemote(reserved.id, association)
                        listOf(reserved.copy(remote = association))
                    }
                }.singleOrNull() ?: return JobStep.Yield
        // Reservation and association commit together before remote I/O.
        return accept(
            work = work,
            permit = permit,
            reaction = reaction,
            reply = remote.start(permit.id, prepared.command),
        )
    }

    fun recover(
        work: JobWork,
        permit: RemotePermit?,
        kind: ProcessingKind,
    ): JobStep {
        if (permit == null) return JobStep.Yield
        val reaction = reactions.getValue(kind)
        // Legacy synchronous calls have no operation ID. Their absence cannot be established by this protocol.
        val associated = permit.remote ?: return JobStep.Unknown("LEGACY_REMOTE_PROOF_REQUIRED")
        val id = UUID.fromString(associated.id)
        val answer = remote.find(id)
        val fenced = if (answer == ProcessingReply.Missing) remote.cancel(id) else answer
        return accept(work = work, permit = permit.copy(remote = associated), reaction = reaction, reply = fenced)
    }

    private fun accept(
        work: JobWork,
        permit: RemotePermit,
        reaction: ProcessingReaction,
        reply: ProcessingReply,
    ): JobStep {
        val answer = await(permit.id, reply, POLL_ATTEMPTS)
        if (answer !is ProcessingReply.Found) return JobStep.Unknown("REMOTE_OPERATION_UNAVAILABLE")
        val operation = answer.operation
        val association = checkNotNull(permit.remote)
        val command = operation.command
        val sameCommand =
            command == null || (
                command.kind == reaction.kind &&
                    command.processorRevision == association.processor &&
                    command.version == association.context["version"]
            )
        val sameOperation =
            operation.id.toString() == association.id && answer.activeLimit == settings.activeLimit.toLong()
        if (!sameOperation || !sameCommand) return JobStep.Unknown("REMOTE_OPERATION_MISMATCH")
        return when (operation.state) {
            ProcessingState.PENDING, ProcessingState.RUNNING, ProcessingState.CANCELLING -> {
                JobStep.Unknown(
                    "REMOTE_OPERATION_RUNNING",
                )
            }

            ProcessingState.CANCELLED -> {
                retryStopped(work, permit, operation)
            }

            ProcessingState.FAILED, ProcessingState.COMPLETED -> {
                reaction.publish(work, permit, operation)
            }
        }
    }

    private tailrec fun await(
        id: UUID,
        reply: ProcessingReply,
        remaining: Int,
    ): ProcessingReply {
        if (reply !is ProcessingReply.Found || reply.operation.state !in
            setOf(ProcessingState.PENDING, ProcessingState.RUNNING, ProcessingState.CANCELLING) ||
            remaining == 0
        ) {
            return reply
        }
        Thread.sleep(POLL_DELAY_MS)
        return await(id, remote.find(id), remaining - 1)
    }

    private fun retryStopped(
        work: JobWork,
        permit: RemotePermit,
        operation: ProcessingOperation,
    ): JobStep {
        work.capacity.retryTerminated(
            lease = work.lease,
            permitId = permit.id,
            evidence = RemoteTerminationEvidence { "OPERATION_CANCELLED:${operation.id}" },
            reason = "REMOTE_OPERATION_CANCELLED",
        )
        return JobStep.Yield
    }
}
