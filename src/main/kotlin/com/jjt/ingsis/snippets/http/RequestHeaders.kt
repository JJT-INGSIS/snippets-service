package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.identity.ActorContext
import org.springframework.http.HttpHeaders

private const val IDEMPOTENCY_KEY = "Idempotency-Key"
private const val DEVELOPMENT_ACTOR = "X-Dev-Actor-Id"

internal fun HttpHeaders.idempotencyKey(): String? = getOrEmpty(IDEMPOTENCY_KEY).singleOrNull()

internal fun HttpHeaders.actorContext(): ActorContext = ActorContext(getOrEmpty(DEVELOPMENT_ACTOR))
