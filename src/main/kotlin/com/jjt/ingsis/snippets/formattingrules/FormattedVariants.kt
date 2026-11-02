package com.jjt.ingsis.snippets.formattingrules

import com.jjt.ingsis.snippets.storage.ContentReference
import java.util.UUID

data class FormattedVariant(
    val snippetId: UUID,
    val basis: FormattingBasis,
    val derived: ContentReference,
)

sealed interface VariantPublication {
    data class Published(
        val replaced: ContentReference?,
    ) : VariantPublication

    data object CodeChanged : VariantPublication

    data object Superseded : VariantPublication
}

interface FormattedVariants {
    fun find(snippetId: UUID): FormattedVariant?

    fun publish(
        variant: FormattedVariant,
        ownerId: String,
        jobId: UUID,
    ): VariantPublication
}
