package com.jjt.ingsis.snippets.http

import com.fasterxml.jackson.annotation.JsonProperty
import com.jjt.ingsis.snippets.metadata.PageRequest
import com.jjt.ingsis.snippets.users.UserPage

data class UserResponse(
    val id: String,
    val name: String,
)

data class UserPageResponse(
    val page: Int,
    @get:JsonProperty("page_size") val pageSize: Int,
    val count: Int,
    val users: List<UserResponse>,
) {
    companion object {
        fun from(
            page: PageRequest,
            result: UserPage,
        ): UserPageResponse =
            UserPageResponse(
                page = page.number,
                pageSize = page.size,
                count = result.count,
                users = result.users.map { UserResponse(it.id, it.name) },
            )
    }
}
