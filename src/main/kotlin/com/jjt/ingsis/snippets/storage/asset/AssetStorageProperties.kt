package com.jjt.ingsis.snippets.storage.asset

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.net.URISyntaxException
import java.time.Duration

private const val DEFAULT_READ_TIMEOUT_SECONDS = 5L
private const val MAX_TCP_PORT = 65_535

@ConfigurationProperties("storage.asset")
data class AssetStorageProperties(
    val baseUrl: String = "",
    val container: String = "snippets",
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(DEFAULT_READ_TIMEOUT_SECONDS),
    val maxContentBytes: Int = 1_048_576,
) {
    internal fun validate() {
        val uri =
            try {
                URI(baseUrl)
            } catch (_: URISyntaxException) {
                // A malformed URL may contain credentials; do not echo its value in startup errors.
                throw IllegalArgumentException("storage.asset.base-url must be a valid HTTP(S) URL")
            }
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            "storage.asset.base-url must be an absolute HTTP(S) URL"
        }
        require(uri.port == -1 || uri.port in 1..MAX_TCP_PORT) { "storage.asset.base-url has an invalid port" }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "storage.asset.base-url must not contain credentials, query or fragment"
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "storage.asset.base-url must not contain a path" }
        require(Regex("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]").matches(container) && !container.contains("--")) {
            "storage.asset.container must be a valid blob container name"
        }
        require(connectTimeout > Duration.ZERO && readTimeout > Duration.ZERO) { "Asset timeouts must be positive" }
        AssetEnvelope.maxWireBytes(maxContentBytes)
    }
}
