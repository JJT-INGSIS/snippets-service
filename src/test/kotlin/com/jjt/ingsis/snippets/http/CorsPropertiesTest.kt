package com.jjt.ingsis.snippets.http

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class CorsPropertiesTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(Properties::class.java)

    @Test
    fun `origins default to empty and bind an explicit comma separated list`() {
        runner.run { assertThat(it.getBean(CorsProperties::class.java).allowedOrigins).isEmpty() }
        runner.withPropertyValues("snippets.cors.allowed-origins=").run {
            assertThat(it.getBean(CorsProperties::class.java).allowedOrigins).isEmpty()
        }
        runner.withPropertyValues("snippets.cors.allowed-origins=http://localhost:5173,https://ui.example.org").run {
            assertThat(it.getBean(CorsProperties::class.java).allowedOrigins)
                .containsExactly("http://localhost:5173", "https://ui.example.org")
        }
    }

    @Test
    fun `malformed origins fail rather than silently opening access`() {
        listOf(
            "*",
            "null",
            "https://*.example.org",
            "https://ui.example.org/",
            "https://ui.example.org/path",
            "https://user:password@ui.example.org",
            "https://ui.example.org?key=value",
            "https://ui.example.org#hash",
            "file:///tmp",
            "http://localhost:0",
            "http://localhost:65536",
            "not an origin",
        ).forEach { origin ->
            assertThatThrownBy { CorsProperties(listOf(origin)) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        runner.withPropertyValues("snippets.cors.allowed-origins=*").run { assertThat(it).hasFailed() }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CorsProperties::class)
    class Properties
}
