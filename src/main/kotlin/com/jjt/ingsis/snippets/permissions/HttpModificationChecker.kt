package com.jjt.ingsis.snippets.permissions

import org.springframework.stereotype.Component
import java.util.UUID

private const val OK = 200
private const val BAD_REQUEST = 400
private const val NOT_FOUND = 404

@Component
class HttpModificationChecker(
    private val transport: PermissionsHttpTransport,
    private val decoder: PermissionsResponseDecoder,
) : ModificationChecker {
    override fun check(
        snippetId: UUID,
        actorId: String,
    ): ModificationPermission {
        if (actorId.isBlank()) return ModificationPermission.Failed(PermissionsFailure.INVALID_REQUEST)
        val path = "/ownership/$snippetId/can-modify"
        return when (val exchange = transport.modification(path, actorId)) {
            PermissionsExchange.Unavailable -> ModificationPermission.Failed(PermissionsFailure.UNAVAILABLE)
            is PermissionsExchange.Received -> decode(exchange.response, path)
        }
    }

    private fun decode(
        response: PermissionsResponse,
        path: String,
    ): ModificationPermission =
        when {
            response.status == OK -> {
                when (decoder.allowed(response)) {
                    true -> ModificationPermission.Allowed
                    false -> ModificationPermission.Denied
                    null -> ModificationPermission.Failed(PermissionsFailure.INVALID_RESPONSE)
                }
            }

            response.status == NOT_FOUND && decoder.problem(response, path, "ownership-not-found") -> {
                ModificationPermission.OwnershipMissing
            }

            response.status == BAD_REQUEST && decoder.problem(response, path, "invalid-request") -> {
                ModificationPermission.Failed(PermissionsFailure.INVALID_REQUEST)
            }

            else -> {
                ModificationPermission.Failed(PermissionsFailure.INVALID_RESPONSE)
            }
        }
}
