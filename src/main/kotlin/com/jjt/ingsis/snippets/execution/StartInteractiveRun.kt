package com.jjt.ingsis.snippets.execution

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import org.springframework.stereotype.Service

@Service
class StartInteractiveRun(
    private val identity: ActorIdentityResolver,
    private val snapshots: ReadExecutionSnapshot,
    private val launcher: RunLauncher,
) {
    fun start(
        snippetId: String,
        context: ActorContext,
    ): RunResult<AuditedRun> {
        val actor =
            identity.resolve(context) as? ActorIdentity.Identified
                ?: return RunResult.Refused(RunFailure.IDENTITY_REQUIRED)
        return when (val snapshot = snapshots.read(snippetId, context)) {
            is RunResult.Refused -> snapshot
            is RunResult.Success -> launcher.launch(actor.actorId, snapshot.value)
        }
    }
}
