package com.jjt.ingsis.snippets.permissions

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

private const val CONNECT_SECONDS = 2L
private const val READ_SECONDS = 5L

@ConfigurationProperties("permissions")
data class PermissionsProperties(
    val baseUrl: String = "http://localhost:8081",
    val connectTimeout: Duration = Duration.ofSeconds(CONNECT_SECONDS),
    val readTimeout: Duration = Duration.ofSeconds(READ_SECONDS),
)
