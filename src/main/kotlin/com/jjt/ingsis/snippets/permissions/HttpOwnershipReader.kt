package com.jjt.ingsis.snippets.permissions

import org.springframework.stereotype.Component
import java.util.UUID

private const val OK = 200
private const val NOT_FOUND = 404
private const val BAD_REQUEST = 400

@Component
class HttpOwnershipReader(
    private val transport: PermissionsHttpTransport,
    private val decoder: PermissionsResponseDecoder,
) : OwnershipReader {
    override fun find(snippetId: UUID): OwnershipLookup {
        val path = "/ownership/$snippetId"
        return when (val exchange = transport.find(path)) {
            PermissionsExchange.Unavailable -> OwnershipLookup.Failed(PermissionsFailure.UNAVAILABLE)
            is PermissionsExchange.Received -> decode(exchange.response, snippetId, path)
        }
    }

    private fun decode(
        response: PermissionsResponse,
        snippetId: UUID,
        path: String,
    ): OwnershipLookup =
        when {
            response.status == OK -> {
                val ownership = decoder.ownership(response, snippetId)
                if (ownership == null) {
                    OwnershipLookup.Failed(PermissionsFailure.INVALID_RESPONSE)
                } else {
                    OwnershipLookup.Found(ownership)
                }
            }

            response.status == NOT_FOUND && decoder.problem(response, path, "ownership-not-found") -> {
                OwnershipLookup.Missing
            }

            response.status == BAD_REQUEST && decoder.problem(response, path, "invalid-request") -> {
                OwnershipLookup.Failed(PermissionsFailure.INVALID_REQUEST)
            }

            else -> {
                OwnershipLookup.Failed(PermissionsFailure.INVALID_RESPONSE)
            }
        }
}
