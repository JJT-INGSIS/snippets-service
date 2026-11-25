package com.jjt.ingsis.snippets.http.security

import com.jjt.ingsis.snippets.identity.AuthenticationModes
import jakarta.servlet.DispatcherType
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain
import java.net.URI

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ApiSecurityConfiguration {
    @Bean
    fun apiSecurity(
        http: HttpSecurity,
        properties: AuthProperties,
        environment: Environment,
    ): SecurityFilterChain {
        require(properties.mode in AuthenticationModes.SUPPORTED) { "Invalid ${AuthenticationModes.PROPERTY}" }
        val developmentProfileActive = environment.acceptsProfiles(Profiles.of(AuthenticationModes.DEVELOPMENT_PROFILE))
        require(properties.mode != AuthenticationModes.DEVELOPMENT || developmentProfileActive) {
            "Dev identity requires the dev profile"
        }
        http
            .csrf { it.disable() }
            .cors { }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
        if (properties.mode == AuthenticationModes.AUTH0) {
            http
                .authorizeHttpRequests {
                    it
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/languages", "/")
                        .permitAll()
                        .anyRequest()
                        .authenticated()
                }.oauth2ResourceServer { it.jwt { } }
        } else {
            http.authorizeHttpRequests { it.anyRequest().permitAll() }
        }
        return http.build()
    }

    @Bean
    @ConditionalOnProperty(name = [AuthenticationModes.PROPERTY], havingValue = AuthenticationModes.AUTH0)
    fun userJwtDecoder(properties: AuthProperties): JwtDecoder {
        val issuer = URI.create(properties.issuer)
        val authority = issuer.scheme == "https" && !issuer.host.isNullOrBlank() && issuer.userInfo == null
        val root = issuer.path == "/" && issuer.query == null && issuer.fragment == null
        require(authority && root) {
            "Auth0 issuer must be an exact HTTPS URL with trailing slash"
        }
        require(properties.audience.isNotBlank()) { "Auth0 audience is required" }
        val uri = properties.jwkSetUri.ifBlank { "${properties.issuer}.well-known/jwks.json" }
        val decoder = NimbusJwtDecoder.withJwkSetUri(uri).jwsAlgorithm(SignatureAlgorithm.RS256).build()
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefaultWithIssuer(properties.issuer),
                UserTokenValidator(properties.audience),
            ),
        )
        return decoder
    }
}
