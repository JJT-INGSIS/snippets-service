package com.jjt.ingsis.snippets.http.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("snippets.auth")
data class AuthProperties(
    val mode: String = "disabled",
    val issuer: String = "",
    val audience: String = "",
    val jwkSetUri: String = "",
)
