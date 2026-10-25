package com.jjt.ingsis.snippets.sharing

import java.util.UUID

sealed interface ShareOutcome {
    data class Shared(
        val snippetId: UUID,
        val userId: String,
        val created: Boolean,
    ) : ShareOutcome

    data class InvalidRequest(
        val field: String,
    ) : ShareOutcome

    data object IdentityRejected : ShareOutcome

    data object InvalidRecipient : ShareOutcome

    data object Missing : ShareOutcome

    data object Denied : ShareOutcome

    data object MetadataUnavailable : ShareOutcome

    data object PermissionsUnavailable : ShareOutcome
}
