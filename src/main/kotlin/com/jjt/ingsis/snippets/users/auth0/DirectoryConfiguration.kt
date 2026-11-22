package com.jjt.ingsis.snippets.users.auth0

import com.jjt.ingsis.snippets.http.security.AuthProperties
import com.jjt.ingsis.snippets.identity.AuthenticationModes
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

@ConfigurationProperties("snippets.directory.auth0")
class Auth0DirectoryProperties(
    val clientId: String = "",
    val clientSecret: String = "",
    val connectTimeout: Duration = Duration.ofSeconds(CONNECT_SECONDS),
    val readTimeout: Duration = Duration.ofSeconds(READ_SECONDS),
) {
    override fun toString(): String =
        "Auth0DirectoryProperties(clientId=$clientId, clientSecret=$HIDDEN_SECRET, " +
            "connectTimeout=$connectTimeout, readTimeout=$readTimeout)"
}

@Configuration(proxyBeanMethods = false)
class DirectoryConfiguration {
    @Bean
    fun userDirectory(
        auth: AuthProperties,
        development: DevelopmentUsers,
        properties: Auth0DirectoryProperties,
    ): UserDirectory =
        when (auth.mode) {
            AuthenticationModes.DEVELOPMENT -> ConfiguredUserDirectory(development)
            AuthenticationModes.AUTH0 -> auth0Directory(auth.issuer, properties)
            else -> UnavailableDirectory
        }

    private fun auth0Directory(
        issuer: String,
        properties: Auth0DirectoryProperties,
    ): UserDirectory {
        if (!hasUsableCredentialsAndTimeouts(properties) || !isSafeHttpsOrigin(issuer)) return UnavailableDirectory
        val managementApi =
            ManagementTransport(
                client = managementClient(issuer, properties),
                credential = ManagementCredential(properties.clientId, properties.clientSecret),
                audience = issuer.removeSuffix("/") + MANAGEMENT_API_PATH,
            )
        return Auth0UserDirectory(managementApi, ManagementTokens(managementApi))
    }

    private fun hasUsableCredentialsAndTimeouts(properties: Auth0DirectoryProperties): Boolean {
        val credentialsPresent = properties.clientId.isNotBlank() && properties.clientSecret.isNotBlank()
        val timeoutsPositive = properties.connectTimeout > Duration.ZERO && properties.readTimeout > Duration.ZERO
        return credentialsPresent && timeoutsPositive
    }

    private fun isSafeHttpsOrigin(issuer: String): Boolean {
        val uri =
            try {
                URI.create(issuer)
            } catch (_: IllegalArgumentException) {
                return false
            }
        val httpsOrigin = uri.scheme == HTTPS_SCHEME && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
        val originWithoutExtras = uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/")
        return httpsOrigin && originWithoutExtras
    }

    private fun managementClient(
        issuer: String,
        properties: Auth0DirectoryProperties,
    ): RestClient =
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
private const val HIDDEN_SECRET = "[hidden]"
private const val HTTPS_SCHEME = "https"
private const val MANAGEMENT_API_PATH = "/api/v2/"
