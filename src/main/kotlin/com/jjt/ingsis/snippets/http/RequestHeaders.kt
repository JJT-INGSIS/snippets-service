package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.identity.ActorContext
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

private const val IDEMPOTENCY_KEY = "Idempotency-Key"
private const val DEVELOPMENT_ACTOR = "X-Dev-Actor-Id"

internal fun HttpHeaders.idempotencyKey(): String? = getOrEmpty(IDEMPOTENCY_KEY).singleOrNull()

internal fun HttpHeaders.actorContext(): ActorContext {
    val authentication = SecurityContextHolder.getContext().authentication
    val actor = (authentication as? JwtAuthenticationToken)?.takeIf { it.isAuthenticated }?.token?.subject
    return ActorContext(getOrEmpty(DEVELOPMENT_ACTOR), actor)
}
