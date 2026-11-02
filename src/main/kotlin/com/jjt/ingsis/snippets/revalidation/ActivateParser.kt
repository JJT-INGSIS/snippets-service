package com.jjt.ingsis.snippets.revalidation

import com.jjt.ingsis.snippets.background.DomainEvent
import com.jjt.ingsis.snippets.background.EventType
import com.jjt.ingsis.snippets.background.NewBackgroundJob
import com.jjt.ingsis.snippets.language.Language
import com.jjt.ingsis.snippets.language.ProcessorCheck
import com.jjt.ingsis.snippets.language.ValidationResult
import org.springframework.stereotype.Service
import java.util.UUID

private const val MIN_OPERATOR_IDENTITY_LENGTH = 4
private const val MAX_OPERATOR_IDENTITY_LENGTH = 200

@Service
class ActivateParser(
    private val language: Language,
    private val activations: ParserActivations,
) {
    fun activate(
        request: ParserActivationRequest,
        administrator: ParserAdministrator,
    ): ParserActivationResult {
        activations.replay(request)?.let { return it }
        val scope = request.scope
        verify(request)?.let { return it }
        return activations.confirm(request, administrator.identity) { activationId, at ->
            val jobId = UUID.randomUUID()
            NewBackgroundJob(
                event =
                    DomainEvent(
                        eventId = UUID.randomUUID(),
                        jobId = jobId,
                        correlationId = activationId,
                        eventType = EventType.ParserVersionActivated,
                        occurredAt = at,
                        scope = mapOf("language" to scope.language, "version" to scope.version),
                        revisions =
                            mapOf(
                                "activation" to activationId.toString(),
                                "processor" to request.processor.value,
                            ),
                    ),
                reaction = "parser-revalidation",
                initiatedBy = administrator.identity,
            )
        }
    }

    private fun verify(request: ParserActivationRequest): ParserActivationResult? {
        val scope = request.scope
        return when {
            language.supportedLanguages().none { it.language == scope.language && scope.version in it.versions } -> {
                ParserActivationResult.UnsupportedScope
            }

            language
                .validationProcessor(
                    scope.language,
                ).checkAgainst(request.processor) != ProcessorCheck.ACCREDITED -> {
                ParserActivationResult.ProcessorUnavailable
            }

            else -> {
                probe(request)
            }
        }
    }

    private fun probe(request: ParserActivationRequest): ParserActivationResult? {
        val response = language.validateAttributed(request.scope.language, request.scope.version, "")
        return when {
            response.result is ValidationResult.UnsupportedVersion ||
                response.result is ValidationResult.UnsupportedLanguage -> {
                ParserActivationResult.UnsupportedScope
            }

            response.result is ValidationResult.Failed ||
                response.processor.checkAgainst(request.processor) != ProcessorCheck.ACCREDITED -> {
                ParserActivationResult.ProcessorUnavailable
            }

            else -> {
                null
            }
        }
    }
}

// Constructed at the operating-system administration boundary, never from a public actor/header.
class ParserAdministrator internal constructor(
    val identity: String,
) {
    init {
        require(
            identity.startsWith("os:") && identity.length in MIN_OPERATOR_IDENTITY_LENGTH..MAX_OPERATOR_IDENTITY_LENGTH,
        )
    }
}
