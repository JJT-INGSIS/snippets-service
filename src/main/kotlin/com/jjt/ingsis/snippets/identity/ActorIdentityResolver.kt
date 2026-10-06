package com.jjt.ingsis.snippets.identity

import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

data class ActorContext(
    val developmentActorHeaders: List<String> = emptyList(),
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
class DevelopmentActorIdentityResolver(
    private val environment: Environment,
) : ActorIdentityResolver {
    override fun resolve(context: ActorContext): ActorIdentity {
        if (!environment.acceptsProfiles(Profiles.of("dev"))) return ActorIdentity.Disabled
        val headers = context.developmentActorHeaders
        return when {
            headers.isEmpty() -> ActorIdentity.Missing
            headers.size != 1 || headers.single().isBlank() -> ActorIdentity.Invalid
            else -> ActorIdentity.Identified(headers.single())
        }
    }
}
