package com.jjt.ingsis.snippets.users.auth0

import com.jjt.ingsis.snippets.http.security.AuthProperties
import com.jjt.ingsis.snippets.metadata.PageRequest
import com.jjt.ingsis.snippets.users.ConfiguredUserDirectory
import com.jjt.ingsis.snippets.users.DevelopmentUsers
import com.jjt.ingsis.snippets.users.DirectoryMembership
import com.jjt.ingsis.snippets.users.DirectorySearch
import com.jjt.ingsis.snippets.users.UserDirectory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

// Not a data class: default toString must never expose the client secret.
@ConfigurationProperties("snippets.directory.auth0")
class Auth0DirectoryProperties(
    val clientId: String = "",
    val clientSecret: String = "",
    val connectTimeout: Duration = Duration.ofSeconds(CONNECT_SECONDS),
    val readTimeout: Duration = Duration.ofSeconds(READ_SECONDS),
)

@Configuration(proxyBeanMethods = false)
class DirectoryConfiguration {
    @Bean
    fun userDirectory(
        auth: AuthProperties,
        development: DevelopmentUsers,
        properties: Auth0DirectoryProperties,
    ): UserDirectory =
        when (auth.mode) {
            "dev" -> ConfiguredUserDirectory(development)
            "auth0" -> auth0Directory(auth.issuer, properties)
            else -> UnavailableDirectory
        }

    private fun auth0Directory(
        issuer: String,
        properties: Auth0DirectoryProperties,
    ): UserDirectory {
        val credentials = properties.clientId.isNotBlank() && properties.clientSecret.isNotBlank()
        val timeouts = properties.connectTimeout > Duration.ZERO && properties.readTimeout > Duration.ZERO
        if (!credentials || !timeouts) {
            return UnavailableDirectory
        }
        val uri =
            try {
                URI.create(issuer)
            } catch (_: IllegalArgumentException) {
                return UnavailableDirectory
            }
        val httpsOrigin = uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
        val noExtras = uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/")
        if (!httpsOrigin || !noExtras) return UnavailableDirectory
        val client =
            RestClient
                .builder()
                .baseUrl(issuer.removeSuffix("/"))
                .requestFactory(
                    JdkClientHttpRequestFactory(
                        HttpClient
                            .newBuilder()
                            .followRedirects(
                                HttpClient.Redirect.NEVER,
                            ).connectTimeout(properties.connectTimeout)
                            .build(),
                    ).apply {
                        setReadTimeout(properties.readTimeout)
                    },
                ).build()
        val transport =
            ManagementTransport(
                client = client,
                credential = ManagementCredential(properties.clientId, properties.clientSecret),
                audience = issuer.removeSuffix("/") + "/api/v2/",
            )
        return Auth0UserDirectory(transport, ManagementTokens(transport))
    }
}

internal object UnavailableDirectory : UserDirectory {
    override fun search(
        name: String,
        excluding: String,
        page: PageRequest,
    ): DirectorySearch = DirectorySearch.Unavailable

    override fun contains(id: String): DirectoryMembership = DirectoryMembership.UNAVAILABLE
}

private const val CONNECT_SECONDS = 2L
private const val READ_SECONDS = 5L
