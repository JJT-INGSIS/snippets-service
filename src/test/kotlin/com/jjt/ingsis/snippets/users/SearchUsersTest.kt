package com.jjt.ingsis.snippets.users

import com.jjt.ingsis.snippets.identity.ActorContext
import com.jjt.ingsis.snippets.identity.ActorIdentity
import com.jjt.ingsis.snippets.identity.ActorIdentityResolver
import com.jjt.ingsis.snippets.metadata.PageRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private val query = UserQuery(name = "an", page = PageRequest(1, 5))

private class RecordingDirectory(
    private val answer: UserPage,
) : UserDirectory {
    override fun search(
        name: String,
        excluding: String,
        page: PageRequest,
    ): DirectorySearch {
        check(name == query.name && excluding == "dev-juan" && page == query.page)
        return DirectorySearch.Found(answer)
    }

    override fun contains(id: String): DirectoryMembership = error("Searching never checks a single id")
}

class SearchUsersTest {
    @Test
    fun `the identified actor is excluded from the search`() {
        val page = UserPage(1, listOf(DirectoryUser("dev-ana", "Ana")))
        val identified = ActorIdentityResolver { ActorIdentity.Identified("dev-juan") }

        val outcome = SearchUsers(identified, RecordingDirectory(page)).search(query, ActorContext())

        assertThat(outcome).isEqualTo(UserSearchOutcome.Found(query.page, page))
    }

    @Test
    fun `without identity the directory is not consulted`() {
        val rejected = ActorIdentityResolver { ActorIdentity.Disabled }
        val untouched =
            object : UserDirectory {
                override fun search(
                    name: String,
                    excluding: String,
                    page: PageRequest,
                ): DirectorySearch = error("The directory must not be searched")

                override fun contains(id: String): DirectoryMembership = error("The directory must not be consulted")
            }

        assertThat(SearchUsers(rejected, untouched).search(query, ActorContext()))
            .isEqualTo(UserSearchOutcome.IdentityRejected)
    }

    @Test
    fun `directory failure and broad searches remain distinct from an empty successful page`() {
        listOf(
            DirectorySearch.Unavailable to UserSearchOutcome.DirectoryUnavailable,
            DirectorySearch.RefineSearch to UserSearchOutcome.RefineSearch,
        ).forEach { (answer, expected) ->
            val directory =
                object : UserDirectory {
                    override fun search(
                        name: String,
                        excluding: String,
                        page: PageRequest,
                    ): DirectorySearch = answer

                    override fun contains(id: String): DirectoryMembership = error("not a membership request")
                }
            val actor = ActorIdentityResolver { ActorIdentity.Identified("auth0|alice") }
            assertThat(SearchUsers(actor, directory).search(query, ActorContext())).isEqualTo(expected)
        }
    }
}
