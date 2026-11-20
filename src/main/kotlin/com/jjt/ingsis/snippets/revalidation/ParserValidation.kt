package com.jjt.ingsis.snippets.revalidation

import com.jjt.ingsis.snippets.background.JobLease
import com.jjt.ingsis.snippets.language.Diagnostic
import com.jjt.ingsis.snippets.storage.ContentReference
import java.util.UUID

data class ParserValidationSnapshot(
    val snippetId: UUID,
    val scope: ParserScope,
    val reference: ContentReference,
)

interface ParserValidations {
    // Called inside the fenced selection commit; pin source references before publishing job items.
    fun select(
        jobId: UUID,
        scope: ParserScope,
        after: UUID?,
        limit: Int,
    ): List<ParserValidationSnapshot>

    fun snapshot(
        jobId: UUID,
        snippetId: UUID,
    ): ParserValidationSnapshot?

    fun current(
        snapshot: ParserValidationSnapshot,
        activationId: UUID,
    ): Boolean

    fun publish(
        snapshot: ParserValidationSnapshot,
        activation: ParserActivation,
        resultId: UUID,
        valid: Boolean,
        diagnostics: List<Diagnostic>,
    ): Boolean
}

interface ParserValidationAdmission {
    fun acquire(
        lease: JobLease,
        itemId: UUID,
    ): UUID?

    fun release(token: UUID)
}
