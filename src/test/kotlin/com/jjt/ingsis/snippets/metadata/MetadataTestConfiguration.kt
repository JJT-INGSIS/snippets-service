package com.jjt.ingsis.snippets.metadata

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID

@TestConfiguration(proxyBeanMethods = false)
class MetadataTestConfiguration {
    @Bean(initMethod = "start", destroyMethod = "stop")
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:18.6-bookworm")

    @Bean
    fun postgresProperties(postgres: PostgreSQLContainer): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
}

internal fun creationRequest(): CreationRequest =
    CreationRequest(
        key = UUID.randomUUID(),
        actorId = "dev-juan",
        fingerprint = UUID.randomUUID().toString(),
        details = SnippetDetails(name = "Example", description = null, language = "printscript", version = "1.0"),
    )
