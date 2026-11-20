package com.jjt.ingsis.snippets.users.auth0

import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.io.IOException

private const val MAX_RESPONSE_BYTES = 1_048_576

internal class ManagementCredential(
    val clientId: String,
    val secret: String,
)

internal data class ManagementRequest(
    val path: String,
    val variables: Map<String, Any> = emptyMap(),
    val query: Map<String, Any> = emptyMap(),
)

internal class ManagementResponse(
    val status: Int,
    val json: Boolean,
    val body: String,
)

internal sealed interface ManagementExchange {
    data class Received(
        val response: ManagementResponse,
    ) : ManagementExchange

    data object Unavailable : ManagementExchange
}

internal class ManagementTransport(
    private val client: RestClient,
    private val credential: ManagementCredential,
    private val audience: String,
) {
    fun token(): ManagementExchange =
        exchange(
            client.post().uri("/oauth/token").contentType(MediaType.APPLICATION_JSON).body(
                mapOf(
                    "grant_type" to "client_credentials",
                    "client_id" to credential.clientId,
                    "client_secret" to credential.secret,
                    "audience" to audience,
                    "scope" to "read:users",
                ),
            ),
        )

    fun get(
        request: ManagementRequest,
        token: String,
    ): ManagementExchange =
        exchange(
            client
                .get()
                .uri { builder ->
                    builder
                        .path(request.path)
                        .apply {
                            request.query.keys.forEach { key -> queryParam(key, "{$key}") }
                        }.build(request.variables + request.query)
                }.headers { it.setBearerAuth(token) },
        )

    private fun exchange(request: RestClient.RequestHeadersSpec<*>): ManagementExchange =
        try {
            request.exchange { _, response ->
                val bytes = response.body.readNBytes(MAX_RESPONSE_BYTES + 1)
                if (bytes.size > MAX_RESPONSE_BYTES) {
                    ManagementExchange.Unavailable
                } else {
                    val type = response.headers.contentType
                    ManagementExchange.Received(
                        ManagementResponse(
                            status = response.statusCode.value(),
                            json = type != null && MediaType.APPLICATION_JSON.isCompatibleWith(type),
                            body = bytes.toString(Charsets.UTF_8),
                        ),
                    )
                }
            }
        } catch (_: RestClientException) {
            ManagementExchange.Unavailable
        } catch (_: IOException) {
            ManagementExchange.Unavailable
        } catch (_: InvalidMediaTypeException) {
            ManagementExchange.Unavailable
        }
}
