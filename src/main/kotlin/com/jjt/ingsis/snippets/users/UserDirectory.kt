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

interface UserDirectory {
    fun search(
        name: String,
        excluding: String,
        page: PageRequest,
    ): UserPage

    fun contains(id: String): Boolean
}
