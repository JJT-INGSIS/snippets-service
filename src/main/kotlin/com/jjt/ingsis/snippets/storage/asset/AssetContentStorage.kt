package com.jjt.ingsis.snippets.storage.asset

import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.DeleteResult
import com.jjt.ingsis.snippets.storage.InvalidContentReference
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StorageUnavailable
import com.jjt.ingsis.snippets.storage.StoreResult
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import java.util.UUID

private const val OK = 200
private const val CREATED = 201
private const val NO_CONTENT = 204
private const val NOT_FOUND = 404

class AssetContentStorage(
    private val transport: AssetHttpTransport,
    private val maxContentBytes: Int,
) : SnippetContentStorage {
    private val logger = LoggerFactory.getLogger(AssetContentStorage::class.java)

    override fun store(code: String): StoreResult {
        val bytes = AssetEnvelope.encode(code, maxContentBytes) ?: return StorageUnavailable
        val reference = ContentReference(UUID.randomUUID().toString())
        val response = transport.exchange(HttpMethod.PUT, reference.value, bytes)
        if (response is AssetExchange.Received && response.status in setOf(OK, CREATED) && response.body.isEmpty()) {
            return StoreResult.Stored(reference)
        }
        logger.warn(
            "Asset PUT was not confirmed; key={}; status={}; outcome may be uncertain",
            reference.value,
            (response as? AssetExchange.Received)?.status,
        )
        return StorageUnavailable
    }

    override fun read(reference: ContentReference): ReadResult {
        val key = canonicalKey(reference) ?: return InvalidContentReference
        return when (val response = transport.exchange(HttpMethod.GET, key)) {
            AssetExchange.Unavailable -> {
                StorageUnavailable
            }

            is AssetExchange.Received -> {
                when (response.status) {
                    NOT_FOUND -> ReadResult.Missing
                    OK -> decode(response)
                    else -> StorageUnavailable
                }
            }
        }
    }

    override fun delete(reference: ContentReference): DeleteResult {
        val key = canonicalKey(reference) ?: return InvalidContentReference
        val response = transport.exchange(HttpMethod.DELETE, key)
        if (response is AssetExchange.Received &&
            response.status in setOf(NO_CONTENT, NOT_FOUND)
        ) {
            return DeleteResult.Deleted
        }
        logger.warn("Asset DELETE was not confirmed; key={}; outcome may be uncertain", key)
        return StorageUnavailable
    }

    private fun decode(response: AssetExchange.Received): ReadResult {
        val type = response.contentType ?: return StorageUnavailable
        val supported =
            type.isCompatibleWith(MediaType.APPLICATION_OCTET_STREAM) ||
                type.isCompatibleWith(MediaType.TEXT_PLAIN)
        if (!supported || type.charset?.let { it != Charsets.UTF_8 } == true) return StorageUnavailable
        return AssetEnvelope.decode(response.body, maxContentBytes)?.let { ReadResult.Found(it) } ?: StorageUnavailable
    }

    private fun canonicalKey(reference: ContentReference): String? =
        try {
            UUID.fromString(reference.value).toString().takeIf { it == reference.value }
        } catch (_: IllegalArgumentException) {
            null
        }
}
