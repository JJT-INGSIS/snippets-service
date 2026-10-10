package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.permissions.OwnershipRegistrar
import com.jjt.ingsis.snippets.permissions.OwnershipRegistration
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CreationOwner(
    private val registrar: OwnershipRegistrar,
) {
    fun register(
        snippetId: UUID,
        ownerId: String,
    ): CreationFailure? =
        when (registrar.register(snippetId, ownerId)) {
            is OwnershipRegistration.Registered -> null
            OwnershipRegistration.Conflict -> CreationFailure.OWNER_CONFLICT
            is OwnershipRegistration.Failed -> CreationFailure.PERMISSIONS_UNAVAILABLE
        }
}
