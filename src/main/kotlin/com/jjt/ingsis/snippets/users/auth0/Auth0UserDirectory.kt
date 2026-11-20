package com.jjt.ingsis.snippets.users.auth0

import com.jjt.ingsis.snippets.metadata.PageRequest
import com.jjt.ingsis.snippets.users.DirectoryMembership
import com.jjt.ingsis.snippets.users.DirectorySearch
import com.jjt.ingsis.snippets.users.UserDirectory
import org.springframework.http.HttpStatus

internal class Auth0UserDirectory(
    private val transport: ManagementTransport,
    private val tokens: ManagementTokens,
) : UserDirectory {
    private val decoder = ManagementUsersDecoder()

    override fun search(
        name: String,
        excluding: String,
        page: PageRequest,
    ): DirectorySearch {
        if (page.offset + page.size > SEARCH_LIMIT) return DirectorySearch.RefineSearch
        return when (val result = get(directoryQuery(name, excluding, page))) {
            ManagementExchange.Unavailable -> DirectorySearch.Unavailable
            is ManagementExchange.Received -> decoder.page(result.response, excluding, page)
        }
    }

    override fun contains(id: String): DirectoryMembership {
        if (id.isBlank()) return DirectoryMembership.ABSENT
        val request =
            ManagementRequest(
                path = "/api/v2/users/{id}",
                variables = mapOf("id" to id),
                query = mapOf("fields" to "user_id", "include_fields" to true),
            )
        return when (val result = get(request)) {
            ManagementExchange.Unavailable -> DirectoryMembership.UNAVAILABLE
            is ManagementExchange.Received -> decoder.member(result.response, id)
        }
    }

    private fun get(request: ManagementRequest): ManagementExchange =
        when (val access = tokens.access()) {
            ManagementAccess.Unavailable -> {
                ManagementExchange.Unavailable
            }

            is ManagementAccess.Granted -> {
                transport.get(request, access.value).also {
                    if (it is ManagementExchange.Received &&
                        it.response.status == HttpStatus.UNAUTHORIZED.value()
                    ) {
                        tokens.invalidate(access.value)
                    }
                }
            }
        }
}
