package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.identity.AuthenticationModes
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.cors.CorsConfiguration as SpringCorsConfiguration

@Configuration(proxyBeanMethods = false)
class CorsConfiguration {
    @Bean
    fun corsConfigurationSource(
        properties: CorsProperties,
        environment: Environment,
    ): CorsConfigurationSource {
        val source = UrlBasedCorsConfigurationSource()
        if (properties.allowedOrigins.isEmpty()) return source
        val policy =
            SpringCorsConfiguration().apply {
                allowedOrigins = properties.allowedOrigins
                allowedHeaders =
                    listOf("Content-Type", "Idempotency-Key", "Authorization") +
                    if (developmentIdentityEnabled(environment)) {
                        listOf("X-Dev-Actor-Id")
                    } else {
                        emptyList()
                    }
                allowCredentials = false
                maxAge = CORS_PREFLIGHT_MAX_AGE_SECONDS
            }
        mapOf(
            "/snippets" to listOf("GET", "POST"),
            "/snippets/*" to listOf("GET", "PUT"),
            "/snippets/*/shares/*" to listOf("PUT"),
            "/snippets/*/formatted" to listOf("GET"),
            "/snippets/*/tests" to listOf("GET", "POST"),
            "/snippets/*/tests/*/executions" to listOf("POST"),
            "/snippets/*/executions" to listOf("POST"),
            "/executions/*" to listOf("GET"),
            "/executions/*/events" to listOf("GET"),
            "/executions/*/inputs" to listOf("POST"),
            "/executions/*/cancel" to listOf("POST"),
            "/users" to listOf("GET"),
            "/languages" to listOf("GET"),
            "/formatting-rules/*/*" to listOf("GET", "PUT"),
            "/linting-rules/*/*" to listOf("GET", "PUT"),
            "/jobs/*" to listOf("GET"),
            "/snippets/*/automatic-tests" to listOf("GET"),
        ).forEach { (path, methods) ->
            source.registerCorsConfiguration(
                path,
                SpringCorsConfiguration(policy).apply { allowedMethods = methods },
            )
        }
        return source
    }
}

private fun developmentIdentityEnabled(environment: Environment): Boolean {
    val developmentProfileActive = environment.acceptsProfiles(Profiles.of(AuthenticationModes.DEVELOPMENT_PROFILE))
    val configuredMode = environment.getProperty(AuthenticationModes.PROPERTY, AuthenticationModes.DISABLED)
    return developmentProfileActive && configuredMode == AuthenticationModes.DEVELOPMENT
}

private const val CORS_PREFLIGHT_MAX_AGE_SECONDS = 300L
