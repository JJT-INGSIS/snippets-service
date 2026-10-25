package com.jjt.ingsis.snippets.users

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.metadata.PageRequest
import org.springframework.stereotype.Service

data class UserQuery(
    val name: String,
    val page: PageRequest,
)

sealed interface UserSearchOutcome {
    data class Found(
        val page: PageRequest,
        val result: UserPage,
    ) : UserSearchOutcome

    data object IdentityRejected : UserSearchOutcome
}

@Service
class SearchUsers(
    private val identityResolver: ActorIdentityResolver,
    private val directory: UserDirectory,
) {
    fun search(
        query: UserQuery,
        context: ActorContext,
    ): UserSearchOutcome {
        val identity = identityResolver.resolve(context)
        if (identity !is ActorIdentity.Identified) return UserSearchOutcome.IdentityRejected

        return UserSearchOutcome.Found(query.page, directory.search(query.name, identity.actorId, query.page))
    }
}
