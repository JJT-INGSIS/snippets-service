package com.jjt.ingsis.snippets.users.auth0

import com.jjt.ingsis.snippets.http.security.AuthProperties
import com.jjt.ingsis.snippets.users.DevelopmentUsers
import com.jjt.ingsis.snippets.users.UserDirectory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Import

@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties::class, Auth0DirectoryProperties::class, DevelopmentUsers::class)
@Import(DirectoryConfiguration::class)
internal class DirectoryBindingConfiguration

class DirectoryBindingTest {
    @Test
    fun `external properties bind the separate credential and select the remote adapter without network startup`() {
        ApplicationContextRunner()
            .withUserConfiguration(DirectoryBindingConfiguration::class.java)
            .withPropertyValues(
                "snippets.auth.mode=auth0",
                "snippets.auth.issuer=https://tenant.fixture/",
                "snippets.directory.auth0.client-id=read-client",
                "snippets.directory.auth0.client-secret=fixture-secret",
            ).run { context ->
                assertThat(context.startupFailure).isNull()
                assertThat(context.getBean(UserDirectory::class.java)).isInstanceOf(Auth0UserDirectory::class.java)
                val properties = context.getBean(Auth0DirectoryProperties::class.java)
                assertThat(properties.clientId).isEqualTo("read-client")
                assertThat(properties.clientSecret).isEqualTo("fixture-secret")
            }
    }
}
