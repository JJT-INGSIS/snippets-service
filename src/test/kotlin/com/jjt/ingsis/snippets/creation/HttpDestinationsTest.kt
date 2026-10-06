package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.language.printscript.PrintScriptProperties
import com.jjt.ingsis.snippets.permissions.PermissionsProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.time.Duration

class HttpDestinationsTest {
    @Test
    fun `Compose URL reaches Language configuration`() {
        runner(mapOf("PRINTSCRIPT_BASE_URL" to "http://printscript-service:8080")).run { context ->
            assertEquals("http://printscript-service:8080", context.getBean(PrintScriptProperties::class.java).baseUrl)
        }
    }

    @Test
    fun `direct Language override takes precedence`() {
        runner(
            mapOf(
                "PRINTSCRIPT_BASE_URL" to "http://printscript-service:8080",
                "LANGUAGE_PRINTSCRIPT_BASE_URL" to "http://override:9090",
            ),
        ).run { context ->
            assertEquals("http://override:9090", context.getBean(PrintScriptProperties::class.java).baseUrl)
        }
    }

    @Test
    fun `Permissions reads Compose URL and bounded timeouts`() {
        runner(mapOf("PERMISSIONS_BASE_URL" to "http://permissions-service:8080")).run { context ->
            val properties = context.getBean(PermissionsProperties::class.java)
            assertEquals("http://permissions-service:8080", properties.baseUrl)
            assertEquals(Duration.ofSeconds(2), properties.connectTimeout)
            assertEquals(Duration.ofSeconds(5), properties.readTimeout)
        }
    }

    private fun runner(environment: Map<String, String>): ApplicationContextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(DestinationsConfiguration::class.java)
            .withInitializer { context ->
                ConfigDataApplicationContextInitializer().initialize(context)
                context.environment.propertySources.addFirst(
                    SystemEnvironmentPropertySource("test-environment", environment),
                )
            }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(PrintScriptProperties::class, PermissionsProperties::class)
class DestinationsConfiguration
