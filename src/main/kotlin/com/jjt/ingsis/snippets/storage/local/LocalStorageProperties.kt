package com.jjt.ingsis.snippets.storage.local

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("storage.local")
data class LocalStorageProperties(
    val directory: String = "build/snippet-content",
)
