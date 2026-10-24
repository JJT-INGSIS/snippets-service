package com.jjt.ingsis.snippets.permissions

import org.springframework.stereotype.Component
import java.util.UUID

private const val OK = 200
private const val BAD_REQUEST = 400
private const val NOT_FOUND = 404

@Component
class HttpAccessChecker(
    private val transport: PermissionsHttpTransport,
    private val decoder: PermissionsResponseDecoder,
) : AccessChecker {
    override fun check(
        snippetId: UUID,
        actorId: String,
    ): AccessLookup {
        if (actorId.isBlank()) return AccessLookup.Failed(PermissionsFailure.INVALID_REQUEST)
        val path = "/ownership/$snippetId/access"
        return when (val exchange = transport.access(path, actorId)) {
            PermissionsExchange.Unavailable -> AccessLookup.Failed(PermissionsFailure.UNAVAILABLE)
            is PermissionsExchange.Received -> decode(exchange.response, snippetId, path)
        }
    }

    private fun decode(
        response: PermissionsResponse,
        snippetId: UUID,
        path: String,
    ): AccessLookup =
        when {
            response.status == OK -> {
                decoder.accessTo(response, snippetId) ?: AccessLookup.Failed(PermissionsFailure.INVALID_RESPONSE)
            }

            response.status == NOT_FOUND && decoder.problem(response, path, "ownership-not-found") -> {
                AccessLookup.Missing
            }

            response.status == BAD_REQUEST && decoder.problem(response, path, "invalid-request") -> {
                AccessLookup.Failed(PermissionsFailure.INVALID_REQUEST)
            }

            else -> {
                AccessLookup.Failed(PermissionsFailure.INVALID_RESPONSE)
            }
        }
}
