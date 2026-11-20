package com.jjt.ingsis.snippets.reformatting

import com.jjt.ingsis.snippets.background.DomainEvent
import com.jjt.ingsis.snippets.formattingrules.FormattingBasis
import com.jjt.ingsis.snippets.formattingrules.FormattingScope

data class ReformattingTarget(
    val scope: FormattingScope,
    val rulesRevision: String,
) {
    fun matches(basis: FormattingBasis): Boolean =
        basis.language == scope.language && basis.version == scope.version && basis.rules == rulesRevision

    companion object {
        fun of(event: DomainEvent): ReformattingTarget? {
            val owner = event.scope["ownerId"] ?: return null
            val language = event.scope["language"] ?: return null
            val version = event.scope["version"] ?: return null
            val rules = event.revisions["formattingRules"] ?: return null
            return ReformattingTarget(FormattingScope(owner, language, version), rules)
        }
    }
}
