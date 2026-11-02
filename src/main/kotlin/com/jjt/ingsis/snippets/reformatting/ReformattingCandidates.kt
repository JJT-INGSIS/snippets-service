package com.jjt.ingsis.snippets.reformatting

import com.jjt.ingsis.snippets.formattingrules.FormattingScope
import com.jjt.ingsis.snippets.storage.ContentReference
import java.util.UUID

data class ReformattingCandidate(
    val id: UUID,
    val reference: ContentReference,
)

fun interface ReformattingCandidates {
    fun page(
        owned: Set<UUID>,
        scope: FormattingScope,
        after: UUID?,
        limit: Int,
    ): List<ReformattingCandidate>
}
