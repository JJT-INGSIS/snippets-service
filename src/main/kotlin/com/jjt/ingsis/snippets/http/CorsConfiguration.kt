package com.jjt.ingsis.snippets.http

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter
import org.springframework.web.cors.CorsConfiguration as SpringCorsConfiguration

@Configuration(proxyBeanMethods = false)
class CorsConfiguration {
    @Bean
    fun corsFilter(
        properties: CorsProperties,
        environment: Environment,
    ): CorsFilter {
        val source = UrlBasedCorsConfigurationSource()
        // No cross-origin grants by default. A proxy can rewrite Host while preserving Origin.
        if (properties.allowedOrigins.isEmpty()) return CorsFilter(source)
        val policy =
            SpringCorsConfiguration().apply {
                allowedOrigins = properties.allowedOrigins
                allowedHeaders =
                    listOf("Content-Type", "Idempotency-Key") +
                    if (environment.acceptsProfiles(Profiles.of("dev"))) listOf("X-Dev-Actor-Id") else emptyList()
                allowCredentials = false
                maxAge = 300L
            }
        mapOf(
            "/snippets" to listOf("GET", "POST"),
            "/snippets/*" to listOf("GET", "PUT"),
            "/snippets/*/shares/*" to listOf("PUT"),
            "/users" to listOf("GET"),
            "/languages" to listOf("GET"),
        ).forEach { (path, methods) ->
            source.registerCorsConfiguration(
                path,
                SpringCorsConfiguration(policy).apply { allowedMethods = methods },
            )
        }
        return CorsFilter(source)
    }
}
