package com.jjt.ingsis.snippets.http.security

import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt

/** Provider-independent signature/time/issuer checks run before these API/user constraints. */
class UserTokenValidator(
    private val audience: String,
) : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        val machine =
            token.getClaimAsString("gty") == "client-credentials" || token.subject?.endsWith("@clients") == true
        val requiredClaims = !token.subject.isNullOrBlank() && token.expiresAt != null
        return if (token.audience?.contains(audience) == true && requiredClaims && !machine) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(
                OAuth2Error("invalid_token", "A valid user access token for this API is required", null),
            )
        }
    }
}
