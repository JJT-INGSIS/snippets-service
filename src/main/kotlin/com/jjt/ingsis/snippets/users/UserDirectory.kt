package com.jjt.ingsis.snippets.users

import com.jjt.ingsis.snippets.metadata.PageRequest

data class DirectoryUser(
    val id: String,
    val name: String,
)

data class UserPage(
    val count: Int,
    val users: List<DirectoryUser>,
)

sealed interface DirectorySearch {
    data class Found(
        val page: UserPage,
    ) : DirectorySearch

    data object Unavailable : DirectorySearch

    data object RefineSearch : DirectorySearch
}

enum class DirectoryMembership { PRESENT, ABSENT, UNAVAILABLE }

interface UserDirectory {
    fun search(
        name: String,
        excluding: String,
        page: PageRequest,
    ): DirectorySearch

    fun contains(id: String): DirectoryMembership
}
