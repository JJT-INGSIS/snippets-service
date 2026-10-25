package com.jjt.ingsis.snippets.permissions

import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder
import java.util.UUID

private const val OK = 200
private const val CREATED = 201
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404
private const val UNPROCESSABLE = 422

@Component
class HttpShareRegistrar(
    private val transport: PermissionsHttpTransport,
    private val decoder: PermissionsResponseDecoder,
) : ShareRegistrar {
    override fun register(
        snippetId: UUID,
        actorId: String,
        recipientId: String,
    ): ShareRegistration {
        if (actorId.isBlank() || recipientId.isBlank()) {
            return ShareRegistration.Failed(PermissionsFailure.INVALID_REQUEST)
        }
        val path = "/ownership/$snippetId/shares/{recipientId}"
        val expectedPath =
            UriComponentsBuilder
                .fromPath(path)
                .encode()
                .buildAndExpand(recipientId)
                .toUriString()
        return when (val exchange = transport.share(path, recipientId, actorId)) {
            PermissionsExchange.Unavailable -> {
                ShareRegistration.Failed(PermissionsFailure.UNAVAILABLE)
            }

            is PermissionsExchange.Received -> {
                decode(response = exchange.response, id = snippetId, recipient = recipientId, path = expectedPath)
            }
        }
    }

    private fun decode(
        response: PermissionsResponse,
        id: UUID,
        recipient: String,
        path: String,
    ): ShareRegistration =
        when {
            response.status in listOf(OK, CREATED) && decoder.confirmsShare(response, id, recipient) -> {
                ShareRegistration.Registered(response.status == CREATED)
            }

            response.status == FORBIDDEN -> {
                decoder.shareDenial(response, path) ?: ShareRegistration.Failed(PermissionsFailure.INVALID_RESPONSE)
            }

            response.status == NOT_FOUND && decoder.problem(response, path, "ownership-not-found") -> {
                ShareRegistration.Missing
            }

            response.status == UNPROCESSABLE && decoder.problem(response, path, "invalid-recipient") -> {
                ShareRegistration.InvalidRecipient
            }

            else -> {
                ShareRegistration.Failed(PermissionsFailure.INVALID_RESPONSE)
            }
        }
}
