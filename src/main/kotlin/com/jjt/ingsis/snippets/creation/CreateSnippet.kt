package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.metadata.SnippetState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class CreateSnippet(
    private val preparation: PrepareCreation,
    private val content: CreationContent,
    private val owner: CreationOwner,
    private val confirmation: CreationConfirmation,
) {
    private val logger = LoggerFactory.getLogger(CreateSnippet::class.java)

    fun create(
        draft: SnippetDraft,
        keyHeader: String?,
        context: ActorContext,
    ): CreationOutcome =
        when (val prepared = preparation.prepare(draft, keyHeader, context)) {
            is PreparationResult.Rejected -> CreationOutcome.Rejected(prepared.reason)
            PreparationResult.Conflict -> CreationOutcome.Conflict
            is PreparationResult.Prepared -> complete(prepared, draft.code)
        }

    private fun complete(
        prepared: PreparationResult.Prepared,
        code: String,
    ): CreationOutcome {
        val snippet = prepared.metadata
        if (snippet.state == SnippetState.CONFIRMED) {
            return CreationOutcome.AlreadyCreated(snippet)
        }

        val contentFailure = content.store(snippet.id, code)
        if (contentFailure != null) {
            return incomplete(snippet.id, contentFailure)
        }

        val ownerFailure = owner.register(snippet.id, prepared.actorId)
        if (ownerFailure != null) {
            return incomplete(snippet.id, ownerFailure)
        }

        val confirmed =
            confirmation.confirm(snippet.id)
                ?: return incomplete(snippet.id, CreationFailure.METADATA_UNAVAILABLE)

        return CreationOutcome.Created(confirmed)
    }

    private fun incomplete(
        snippetId: UUID,
        failure: CreationFailure,
    ): CreationOutcome {
        logger.warn("The creation of snippet {} stays pending: {}", snippetId, failure)

        return CreationOutcome.Incomplete(failure)
    }
}
