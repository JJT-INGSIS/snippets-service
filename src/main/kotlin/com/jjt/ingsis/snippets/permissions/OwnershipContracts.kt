package com.jjt.ingsis.snippets.permissions

import java.util.UUID

data class Ownership(
    val snippetId: UUID,
    val ownerId: String,
)

enum class PermissionsFailure {
    INVALID_REQUEST,
    UNAVAILABLE,
    INVALID_RESPONSE,
}

sealed interface OwnershipRegistration {
    data class Registered(
        val ownership: Ownership,
        val created: Boolean,
    ) : OwnershipRegistration

    data object Conflict : OwnershipRegistration

    data class Failed(
        val reason: PermissionsFailure,
        val outcomeUncertain: Boolean,
    ) : OwnershipRegistration
}

sealed interface OwnershipLookup {
    data class Found(
        val ownership: Ownership,
    ) : OwnershipLookup

    data object Missing : OwnershipLookup

    data class Failed(
        val reason: PermissionsFailure,
    ) : OwnershipLookup
}

fun interface OwnershipRegistrar {
    fun register(
        snippetId: UUID,
        ownerId: String,
    ): OwnershipRegistration
}

fun interface OwnershipReader {
    fun find(snippetId: UUID): OwnershipLookup
}
