package com.jjt.ingsis.snippets.permissions

import org.springframework.stereotype.Component
import java.util.UUID

private const val OK = 200
private const val CREATED = 201
private const val BAD_REQUEST = 400
private const val CONFLICT = 409

@Component
class HttpOwnershipRegistrar(
    private val transport: PermissionsHttpTransport,
    private val decoder: PermissionsResponseDecoder,
) : OwnershipRegistrar {
    override fun register(
        snippetId: UUID,
        ownerId: String,
    ): OwnershipRegistration {
        if (ownerId.isBlank()) return OwnershipRegistration.Failed(PermissionsFailure.INVALID_REQUEST, false)
        val path = "/ownership/$snippetId"
        return when (val exchange = transport.register(path, ownerId)) {
            PermissionsExchange.Unavailable -> {
                OwnershipRegistration.Failed(PermissionsFailure.UNAVAILABLE, true)
            }

            is PermissionsExchange.Received -> {
                decode(response = exchange.response, snippetId = snippetId, ownerId = ownerId, path = path)
            }
        }
    }

    private fun decode(
        response: PermissionsResponse,
        snippetId: UUID,
        ownerId: String,
        path: String,
    ): OwnershipRegistration =
        when {
            response.status == OK || response.status == CREATED -> {
                val ownership = decoder.ownership(response, snippetId)
                if (ownership?.ownerId == ownerId) {
                    OwnershipRegistration.Registered(ownership, created = response.status == CREATED)
                } else {
                    OwnershipRegistration.Failed(PermissionsFailure.INVALID_RESPONSE, true)
                }
            }

            response.status == CONFLICT && decoder.problem(response, path, "owner-conflict") -> {
                OwnershipRegistration.Conflict
            }

            response.status == BAD_REQUEST && decoder.problem(response, path, "invalid-request") -> {
                OwnershipRegistration.Failed(PermissionsFailure.INVALID_REQUEST, false)
            }

            else -> {
                OwnershipRegistration.Failed(PermissionsFailure.INVALID_RESPONSE, true)
            }
        }
}
