package com.jjt.ingsis.snippets.permissions

import org.springframework.stereotype.Component

private const val OK = 200
private const val BAD_REQUEST = 400
private const val PATH = "/access"

@Component
class HttpAccessLister(
    private val transport: PermissionsHttpTransport,
    private val decoder: PermissionsResponseDecoder,
) : AccessLister {
    override fun list(
        actorId: String,
        filter: RelationFilter,
    ): AccessListing {
        if (actorId.isBlank()) return AccessListing.Failed(PermissionsFailure.INVALID_REQUEST)
        return when (val exchange = transport.access(PATH, actorId, filter.name.lowercase())) {
            PermissionsExchange.Unavailable -> AccessListing.Failed(PermissionsFailure.UNAVAILABLE)
            is PermissionsExchange.Received -> decode(exchange.response, actorId)
        }
    }

    private fun decode(
        response: PermissionsResponse,
        actorId: String,
    ): AccessListing =
        when {
            response.status == OK -> {
                val accesses = decoder.accesses(response, actorId)
                if (accesses == null) {
                    AccessListing.Failed(PermissionsFailure.INVALID_RESPONSE)
                } else {
                    AccessListing.Listed(accesses)
                }
            }

            response.status == BAD_REQUEST && decoder.problem(response, PATH, "invalid-request") -> {
                AccessListing.Failed(PermissionsFailure.INVALID_REQUEST)
            }

            else -> {
                AccessListing.Failed(PermissionsFailure.INVALID_RESPONSE)
            }
        }
}
