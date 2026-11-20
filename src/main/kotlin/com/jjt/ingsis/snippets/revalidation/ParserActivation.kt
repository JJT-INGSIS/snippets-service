package com.jjt.ingsis.snippets.revalidation

import com.jjt.ingsis.snippets.background.NewBackgroundJob
import com.jjt.ingsis.snippets.language.ProcessorRevision
import java.time.Instant
import java.util.UUID

private const val MAX_ACTIVATION_REASON_LENGTH = 250

data class ParserScope(
    val language: String,
    val version: String,
) {
    init {
        require(language.isNotBlank() && language == language.trim())
        require(version.isNotBlank() && version == version.trim())
    }
}

data class ParserActivationRequest(
    val requestId: UUID,
    val scope: ParserScope,
    val processor: ProcessorRevision,
    val reason: String,
) {
    init {
        require(reason.isNotBlank() && reason.length <= MAX_ACTIVATION_REASON_LENGTH && reason.none(Char::isISOControl))
    }
}

data class ParserActivation(
    val id: UUID,
    val scope: ParserScope,
    val processor: ProcessorRevision,
    val jobId: UUID,
    val activatedAt: Instant,
)

sealed interface ParserActivationResult {
    data class Confirmed(
        val activation: ParserActivation,
        val replayed: Boolean,
    ) : ParserActivationResult

    data object RequestConflict : ParserActivationResult

    data object ProcessorUnavailable : ParserActivationResult

    data object UnsupportedScope : ParserActivationResult
}

interface ParserActivations {
    fun replay(request: ParserActivationRequest): ParserActivationResult?

    fun active(scope: ParserScope): ParserActivation?

    fun confirm(
        request: ParserActivationRequest,
        operator: String,
        jobFor: (UUID, Instant) -> NewBackgroundJob,
    ): ParserActivationResult
}
