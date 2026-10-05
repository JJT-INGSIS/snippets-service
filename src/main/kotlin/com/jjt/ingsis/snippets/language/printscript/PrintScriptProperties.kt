package com.jjt.ingsis.snippets.language.printscript

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

private const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 2L
private const val DEFAULT_READ_TIMEOUT_SECONDS = 5L

@ConfigurationProperties("language.printscript")
data class PrintScriptProperties(
    val baseUrl: String = "http://localhost:8082",
    val connectTimeout: Duration = Duration.ofSeconds(DEFAULT_CONNECT_TIMEOUT_SECONDS),
    val readTimeout: Duration = Duration.ofSeconds(DEFAULT_READ_TIMEOUT_SECONDS),
)
