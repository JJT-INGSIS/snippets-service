package com.jjt.ingsis.snippets.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class DevelopmentIdentityTest {
    @Test
    fun `development identity is disabled unless dev profile is explicitly active`() {
        val resolver = DevelopmentActorIdentityResolver(MockEnvironment())
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

    private fun resolver(): DevelopmentActorIdentityResolver =
        DevelopmentActorIdentityResolver(MockEnvironment().apply { setActiveProfiles("dev") })
}
