package com.jjt.ingsis.snippets.identity

import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

data class ActorContext(
    val developmentActorHeaders: List<String> = emptyList(),
    val authenticatedActorId: String? = null,
)

sealed interface ActorIdentity {
    data class Identified(
        val actorId: String,
    ) : ActorIdentity

    data object Disabled : ActorIdentity

    data object Missing : ActorIdentity

    data object Invalid : ActorIdentity
}

fun interface ActorIdentityResolver {
    fun resolve(context: ActorContext): ActorIdentity
}

@Component
class ConfiguredActorIdentityResolver(
    private val environment: Environment,
) : ActorIdentityResolver {
    override fun resolve(context: ActorContext): ActorIdentity =
        when (environment.getProperty(AuthenticationModes.PROPERTY, AuthenticationModes.DISABLED)) {
            AuthenticationModes.AUTH0 -> authenticatedIdentity(context)
            AuthenticationModes.DEVELOPMENT -> developmentIdentity(context)
            else -> ActorIdentity.Disabled
        }

    private fun authenticatedIdentity(context: ActorContext): ActorIdentity =
        context.authenticatedActorId
            ?.takeIf { it.isNotBlank() }
            ?.let { ActorIdentity.Identified(it) } ?: ActorIdentity.Missing

    private fun developmentIdentity(context: ActorContext): ActorIdentity {
        val developmentProfileActive = environment.acceptsProfiles(Profiles.of(AuthenticationModes.DEVELOPMENT_PROFILE))
        if (!developmentProfileActive) return ActorIdentity.Disabled
        val headers = context.developmentActorHeaders
        return when {
            headers.isEmpty() -> ActorIdentity.Missing
            headers.size != 1 || headers.single().isBlank() -> ActorIdentity.Invalid
            else -> ActorIdentity.Identified(headers.single())
        }
    }
}
