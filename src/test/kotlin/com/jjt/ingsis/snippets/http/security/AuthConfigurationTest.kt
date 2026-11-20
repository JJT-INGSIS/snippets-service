package com.jjt.ingsis.snippets.http.security

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.mock.env.MockEnvironment
import org.springframework.security.config.annotation.web.builders.HttpSecurity

class AuthConfigurationTest {
    private val configuration = ApiSecurityConfiguration()

    @Test
    fun `unknown mode and dev mode outside dev profile fail before a filter chain is installed`() {
        for (properties in listOf(AuthProperties(mode = "typo"), AuthProperties(mode = "dev"))) {
            assertThatThrownBy {
                configuration.apiSecurity(mock(HttpSecurity::class.java), properties, MockEnvironment())
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `auth0 cannot start without exact HTTPS issuer and audience`() {
        for (properties in listOf(
            AuthProperties(mode = "auth0"),
            AuthProperties(mode = "auth0", issuer = "http://issuer/", audience = "api"),
            AuthProperties(mode = "auth0", issuer = "https://issuer", audience = "api"),
            AuthProperties(mode = "auth0", issuer = "https://user@issuer/", audience = "api"),
            AuthProperties(mode = "auth0", issuer = "https://issuer/?query=/", audience = "api"),
            AuthProperties(mode = "auth0", issuer = "https://issuer/"),
        )) {
            assertThatThrownBy { configuration.userJwtDecoder(properties) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
