package com.jjt.ingsis.snippets.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class DevelopmentIdentityTest {
    @Test
    fun `development identity is disabled unless dev profile is explicitly active`() {
        val resolver = ConfiguredActorIdentityResolver(MockEnvironment())
        assertEquals(ActorIdentity.Disabled, resolver.resolve(ActorContext(listOf("dev-thiago"))))
    }

    @Test
    fun `missing blank and repeated actor headers are rejected`() {
        val resolver = resolver()
        assertEquals(ActorIdentity.Missing, resolver.resolve(ActorContext()))
        assertEquals(ActorIdentity.Invalid, resolver.resolve(ActorContext(listOf(" "))))
        assertEquals(ActorIdentity.Invalid, resolver.resolve(ActorContext(listOf("first", "second"))))
    }

    @Test
    fun `preserves opaque actor IDs without normalization`() {
        val actor = " auth0|Thiago+One "
        assertEquals(ActorIdentity.Identified(actor), resolver().resolve(ActorContext(listOf(actor))))
    }

    @Test
    fun `auth0 ignores every dev header and accepts only the authenticated subject`() {
        val environment =
            MockEnvironment().apply {
                setActiveProfiles("dev")
                setProperty("snippets.auth.mode", "auth0")
            }
        val resolver = ConfiguredActorIdentityResolver(environment)
        assertEquals(ActorIdentity.Missing, resolver.resolve(ActorContext(listOf("forged"))))
        assertEquals(
            ActorIdentity.Identified("auth0|real"),
            resolver.resolve(ActorContext(listOf("forged", "other"), "auth0|real")),
        )
    }

    @Test
    fun `explicit disabled mode rejects identity even in dev and dev ignores authenticated context`() {
        val environment =
            MockEnvironment().apply {
                setActiveProfiles("dev")
                setProperty("snippets.auth.mode", "disabled")
            }
        assertEquals(
            ActorIdentity.Disabled,
            ConfiguredActorIdentityResolver(environment).resolve(ActorContext(listOf("dev-juan"), "auth0|real")),
        )
        assertEquals(ActorIdentity.Missing, resolver().resolve(ActorContext(authenticatedActorId = "auth0|real")))
    }

    private fun resolver(): ConfiguredActorIdentityResolver =
        ConfiguredActorIdentityResolver(
            MockEnvironment().apply {
                setActiveProfiles("dev")
                setProperty("snippets.auth.mode", "dev")
            },
        )
}
