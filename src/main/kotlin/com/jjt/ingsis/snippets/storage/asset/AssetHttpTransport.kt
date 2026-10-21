package com.jjt.ingsis.snippets.storage.asset

import org.springframework.http.HttpMethod
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.io.IOException
import java.net.http.HttpClient

internal sealed interface AssetExchange {
    data class Received(
        val status: Int,
        val body: ByteArray,
        val contentType: MediaType?,
    ) : AssetExchange

    data object Unavailable : AssetExchange
}

class AssetHttpTransport(
    private val properties: AssetStorageProperties,
) {
    init {
        properties.validate()
    }

    private val maxWireBytes = AssetEnvelope.maxWireBytes(properties.maxContentBytes)

    private val client =
        RestClient
            .builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build(),
                ).apply { setReadTimeout(properties.readTimeout) },
            ).build()

    internal fun exchange(
        method: HttpMethod,
        key: String,
        bytes: ByteArray? = null,
    ): AssetExchange =
        try {
            val request =
                client
                    .method(method)
                    .uri { builder -> builder.pathSegment("v1", "asset", properties.container, key).build() }
                    .accept(MediaType.APPLICATION_OCTET_STREAM)
            if (bytes != null) request.contentType(MediaType.APPLICATION_OCTET_STREAM).body(bytes)
            request.exchange { _, response ->
                val body = response.body.readNBytes(maxWireBytes + 1)
                if (body.size > maxWireBytes) {
                    AssetExchange.Unavailable
                } else {
                    AssetExchange.Received(response.statusCode.value(), body, response.headers.contentType)
                }
            }
        } catch (_: RestClientException) {
            AssetExchange.Unavailable
        } catch (_: IOException) {
            AssetExchange.Unavailable
        } catch (_: InvalidMediaTypeException) {
            AssetExchange.Unavailable
        }
}
