package com.jjt.ingsis.snippets.storage.asset

import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.DeleteResult
import com.jjt.ingsis.snippets.storage.InvalidContentReference
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.StorageUnavailable
import com.jjt.ingsis.snippets.storage.StoreResult
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

internal data class AssetRequest(
    val method: String,
    val path: String,
    val bytes: ByteArray,
)

internal class AssetStub(
    private val status: Int = 200,
    private val body: ByteArray = byteArrayOf(),
    private val type: String = "application/octet-stream",
    private val delayMillis: Long = 0,
    private val delayBodyMillis: Long = 0,
) : AutoCloseable {
    val requests = CopyOnWriteArrayList<AssetRequest>()
    private val executor = Executors.newCachedThreadPool()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@AssetStub.executor
            createContext("/") { exchange ->
                exchange.use {
                    requests.add(
                        AssetRequest(it.requestMethod, it.requestURI.toString(), it.requestBody.readAllBytes()),
                    )
                    Thread.sleep(delayMillis)
                    try {
                        it.responseHeaders.add("Content-Type", type)
                        // Chunked bodies prove the client does not rely on Content-Length for its limit.
                        it.sendResponseHeaders(status, if (body.isEmpty()) -1 else 0)
                        Thread.sleep(delayBodyMillis)
                        if (body.isNotEmpty()) it.responseBody.write(body)
                    } catch (_: IOException) {
                        // The client may close the response after its size limit or timeout.
                    }
                }
            }
            start()
        }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    fun storage(
        limit: Int = 1_048_576,
        timeout: Duration = Duration.ofSeconds(2),
    ): AssetContentStorage {
        val properties = AssetStorageProperties(baseUrl = url, readTimeout = timeout, maxContentBytes = limit)
        return AssetContentStorage(AssetHttpTransport(properties), limit)
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

class AssetHttpTest {
    private val reference = ContentReference(UUID.randomUUID().toString())

    @Test
    fun `writes use new opaque keys and a versioned envelope including empty input`() {
        listOf(200, 201).forEach { status ->
            AssetStub(status = status).use { service ->
                val storage = service.storage()
                listOf("", " \r\ná 🚀\n ").forEach { code ->
                    val stored = storage.store(code) as StoreResult.Stored
                    assertThat(UUID.fromString(stored.reference.value).toString()).isEqualTo(stored.reference.value)
                    assertThat(service.requests.last().path).isEqualTo("/v1/asset/snippets/${stored.reference.value}")
                    val body = JsonMapper.builder().build().readTree(service.requests.last().bytes)
                    assertThat(body.get("format").asString()).isEqualTo("snippet-content-v1")
                    assertThat(body.get("code").asString()).isEqualTo(code)
                    assertThat(service.requests.last().method).isEqualTo("PUT")
                }
                assertThat(service.requests.map { it.path }.distinct()).hasSize(2)
            }
        }
    }

    @Test
    fun `reads preserve valid UTF8 but reject malformed bytes representations and oversized bodies`() {
        val code = " \nñ🚀\r\n "
        AssetStub(body = envelope(code)).use { service ->
            assertThat(service.storage().read(reference)).isEqualTo(ReadResult.Found(code))
        }
        AssetStub(body = envelope("")).use { service ->
            assertThat(service.storage().read(reference)).isEqualTo(ReadResult.Found(""))
        }
        AssetStub().use { service ->
            assertThat(service.storage().read(reference)).isEqualTo(StorageUnavailable)
        }
        AssetStub(body = byteArrayOf(0xc3.toByte(), 0x28)).use { service ->
            assertThat(service.storage().read(reference)).isEqualTo(StorageUnavailable)
        }
        listOf("text/html", "application/json", "text/plain;charset=ISO-8859-1", "not a media type").forEach { type ->
            AssetStub(body = "error".toByteArray(), type = type).use { service ->
                assertThat(service.storage().read(reference)).isEqualTo(StorageUnavailable)
            }
        }
        AssetStub(body = ByteArray(AssetEnvelope.maxWireBytes(4) + 1)).use { service ->
            assertThat(service.storage(limit = 4).read(reference)).isEqualTo(StorageUnavailable)
        }
        AssetStub(body = envelope("12345")).use { service ->
            assertThat(service.storage(limit = 4).read(reference)).isEqualTo(StorageUnavailable)
        }
    }

    @Test
    fun `missing reads and idempotent deletes are not service failures`() {
        AssetStub(status = 404).use { service ->
            assertThat(service.storage().read(reference)).isEqualTo(ReadResult.Missing)
            assertThat(service.storage().delete(reference)).isEqualTo(DeleteResult.Deleted)
        }
        AssetStub(status = 204).use { service ->
            assertThat(service.storage().delete(reference)).isEqualTo(DeleteResult.Deleted)
        }
        listOf(400, 401, 403, 500, 503, 302).forEach { status ->
            AssetStub(status = status).use { service ->
                val storage = service.storage()
                assertThat(storage.store("code")).isEqualTo(StorageUnavailable)
                assertThat(storage.read(reference)).isEqualTo(StorageUnavailable)
                assertThat(storage.delete(reference)).isEqualTo(StorageUnavailable)
            }
        }
        AssetStub(body = "unexpected".toByteArray()).use { service ->
            assertThat(service.storage().store("code")).isEqualTo(StorageUnavailable)
        }
    }

    @Test
    fun `invalid references oversized writes and malformed strings do not perform HTTP`() {
        AssetStub().use { service ->
            val storage = service.storage(limit = 4)
            listOf("../secret", "1-1-1-1-1", "%2e%2e", "a/b", "a?b").forEach { invalid ->
                val ref = ContentReference(invalid)
                assertThat(storage.read(ref)).isEqualTo(InvalidContentReference)
                assertThat(storage.delete(ref)).isEqualTo(InvalidContentReference)
            }
            assertThat(storage.store("ñññ")).isEqualTo(StorageUnavailable)
            assertThat(storage.store("12345")).isEqualTo(StorageUnavailable)
            assertThat(storage.store("\uD800")).isEqualTo(StorageUnavailable)
            assertThat(service.requests).isEmpty()
            assertThat(storage.store("ññ")).isInstanceOf(StoreResult.Stored::class.java)
        }
    }

    @Test
    fun `timeouts and unavailable server are failures without automatic retries`() {
        AssetStub(delayMillis = 300).use { service ->
            assertThat(service.storage(timeout = Duration.ofMillis(100)).store("code")).isEqualTo(StorageUnavailable)
            assertThat(service.requests).hasSize(1)
        }
        val properties = AssetStorageProperties(baseUrl = "http://127.0.0.1:1")
        val storage = AssetContentStorage(AssetHttpTransport(properties), properties.maxContentBytes)
        assertThat(storage.read(reference)).isEqualTo(StorageUnavailable)
        assertThat(storage.delete(reference)).isEqualTo(StorageUnavailable)
    }

    @Test
    fun `read timeout also covers a body delayed after headers`() {
        AssetStub(body = envelope("code"), delayBodyMillis = 400).use { service ->
            assertThat(service.storage(timeout = Duration.ofMillis(100)).read(reference))
                .isEqualTo(StorageUnavailable)
        }
    }
}

internal fun envelope(code: String): ByteArray =
    JsonMapper
        .builder()
        .build()
        .writeValueAsBytes(linkedMapOf("format" to "snippet-content-v1", "code" to code))
