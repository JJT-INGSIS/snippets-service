package com.jjt.ingsis.snippets.permissions

import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.http.HttpClient

data class RegisterOwnershipRequest(
    val ownerId: String,
)

internal data class PermissionsResponse(
    val status: Int,
    val contentType: String?,
    val body: String,
)

internal sealed interface PermissionsExchange {
    data class Received(
        val response: PermissionsResponse,
    ) : PermissionsExchange

    data object Unavailable : PermissionsExchange
}

@Component
class PermissionsHttpTransport(
    properties: PermissionsProperties,
) {
    private val client =
        RestClient
            .builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build(),
                ).apply { setReadTimeout(properties.readTimeout) },
            ).build()

    internal fun register(
        path: String,
        ownerId: String,
    ): PermissionsExchange =
        exchange(
            client
                .put()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(RegisterOwnershipRequest(ownerId)),
        )

    internal fun find(path: String): PermissionsExchange = exchange(client.get().uri(path))

    internal fun modification(
        path: String,
        actorId: String,
    ): PermissionsExchange =
        exchange(
            client.get().uri { builder ->
                builder.path(path).queryParam("actorId", "{actorId}").build(actorId)
            },
        )

    private fun exchange(request: RestClient.RequestHeadersSpec<*>): PermissionsExchange =
        try {
            request.exchange { _, response ->
                PermissionsExchange.Received(
                    PermissionsResponse(
                        status = response.statusCode.value(),
                        contentType = response.headers.contentType?.let { "${it.type}/${it.subtype}" },
                        body = response.body.readAllBytes().toString(Charsets.UTF_8),
                    ),
                )
            }
        } catch (_: RestClientException) {
            PermissionsExchange.Unavailable
        } catch (_: java.io.IOException) {
            PermissionsExchange.Unavailable
        } catch (_: org.springframework.http.InvalidMediaTypeException) {
            PermissionsExchange.Unavailable
        }
}
