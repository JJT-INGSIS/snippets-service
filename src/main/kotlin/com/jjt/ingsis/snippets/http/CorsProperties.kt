package com.jjt.ingsis.snippets.http

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

private const val MAX_PORT = 65535

@ConfigurationProperties("snippets.cors")
data class CorsProperties(
    val allowedOrigins: List<String> = emptyList(),
) {
    init {
        require(allowedOrigins.all(::isOrigin)) {
            "snippets.cors.allowed-origins must contain exact HTTP(S) origins without wildcards, paths or credentials"
        }
    }
}

private fun isOrigin(value: String): Boolean {
    val uri = runCatching { URI.create(value) }.getOrNull() ?: return false
    return uri.scheme in setOf("http", "https") &&
        !uri.host.isNullOrBlank() && uri.port in -1..MAX_PORT && uri.port != 0 &&
        uri.rawUserInfo == null && uri.rawPath.isNullOrEmpty() &&
        uri.rawQuery == null && uri.rawFragment == null && '*' !in value
}
