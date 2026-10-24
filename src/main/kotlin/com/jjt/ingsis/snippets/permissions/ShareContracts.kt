package com.jjt.ingsis.snippets.permissions

import java.util.UUID

sealed interface ShareRegistration {
    data class Registered(
        val created: Boolean,
    ) : ShareRegistration

    data object Denied : ShareRegistration

    data object Missing : ShareRegistration

    data object InvalidRecipient : ShareRegistration

    data class Failed(
        val reason: PermissionsFailure,
    ) : ShareRegistration
}

fun interface ShareRegistrar {
    fun register(
        snippetId: UUID,
        actorId: String,
        recipientId: String,
    ): ShareRegistration
}
