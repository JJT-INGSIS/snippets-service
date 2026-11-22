package com.jjt.ingsis.snippets.http.security

import com.jjt.ingsis.snippets.identity.AuthenticationModes
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("snippets.auth")
data class AuthProperties(
    val mode: String = AuthenticationModes.DISABLED,
    val issuer: String = "",
    val audience: String = "",
    val jwkSetUri: String = "",
)
