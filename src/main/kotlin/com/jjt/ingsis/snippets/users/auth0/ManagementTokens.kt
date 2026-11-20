package com.jjt.ingsis.snippets.users.auth0

import org.springframework.http.HttpStatus
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

internal sealed interface ManagementAccess {
    class Granted(
        val value: String,
        val validUntil: Instant,
    ) : ManagementAccess

    data object Unavailable : ManagementAccess
}

// This is a volatile credential cache, never a directory/profile or business-data store.
internal class ManagementTokens(
    private val transport: ManagementTransport,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val cached = AtomicReference<ManagementAccess.Granted?>()
    private val json = JsonMapper.builder().build()

    @Synchronized
    fun access(): ManagementAccess {
        val previous = cached.get()
        if (previous != null && previous.validUntil.isAfter(clock.instant())) return previous
        cached.set(null)
        return when (val response = transport.token()) {
            ManagementExchange.Unavailable -> {
                ManagementAccess.Unavailable
            }

            is ManagementExchange.Received -> {
                decode(response.response).also {
                    if (it is ManagementAccess.Granted) cached.set(it)
                }
            }
        }
    }

    fun invalidate(value: String) {
        cached.updateAndGet { if (it?.value == value) null else it }
    }

    private fun decode(response: ManagementResponse): ManagementAccess {
        if (response.status != HttpStatus.OK.value() || !response.json) return ManagementAccess.Unavailable
        return try {
            val root = json.readTree(response.body)
            val token =
                root?.get("access_token")?.takeIf { it.isString }?.asString()?.takeIf {
                    it.isNotBlank() && it.none { char -> char.isWhitespace() || char.isISOControl() }
                } ?: return ManagementAccess.Unavailable
            val type = root.get("token_type")?.takeIf { it.isString }?.asString()
            val seconds =
                root.get("expires_in")?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong() ?: 0
            val scope =
                root
                    .get("scope")
                    ?.takeIf { it.isString }
                    ?.asString()
                    ?.split(' ')
                    ?.filter(String::isNotBlank)
            if (!type.equals("Bearer", ignoreCase = true) || seconds !in 1..MAX_TOKEN_SECONDS ||
                scope?.toSet() != setOf("read:users")
            ) {
                ManagementAccess.Unavailable
            } else {
                ManagementAccess.Granted(
                    token,
                    clock.instant().plusSeconds(seconds - minOf(SKEW_SECONDS, seconds / EXPIRY_MARGIN_DIVISOR)),
                )
            }
        } catch (_: JacksonException) {
            ManagementAccess.Unavailable
        }
    }
}

private const val SKEW_SECONDS = 30L
private const val MAX_TOKEN_SECONDS = 2_592_000L

private const val EXPIRY_MARGIN_DIVISOR = 10L
