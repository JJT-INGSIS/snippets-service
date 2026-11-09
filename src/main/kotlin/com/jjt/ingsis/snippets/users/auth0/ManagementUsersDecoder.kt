package com.jjt.ingsis.snippets.users.auth0

import com.jjt.ingsis.snippets.metadata.PageRequest
import com.jjt.ingsis.snippets.users.DirectoryMembership
import com.jjt.ingsis.snippets.users.DirectorySearch
import com.jjt.ingsis.snippets.users.DirectoryUser
import com.jjt.ingsis.snippets.users.UserPage
import org.springframework.http.HttpStatus
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

private data class NativePage(
    val total: Long,
    val users: List<DirectoryUser>,
    val start: Long?,
) {
    fun matches(
        requested: PageRequest,
        excluding: String,
    ): Boolean {
        val expected = minOf(requested.size.toLong(), (total - requested.offset).coerceAtLeast(0)).toInt()
        val unique = users.map { it.id }.distinct().size == users.size
        val noActor = users.none { it.id == excluding }
        return users.size == expected && unique && noActor
    }
}

internal class ManagementUsersDecoder {
    private val json = JsonMapper.builder().build()

    fun page(
        response: ManagementResponse,
        excluding: String,
        requested: PageRequest,
    ): DirectorySearch {
        if (response.status != HttpStatus.OK.value() || !response.json) return DirectorySearch.Unavailable
        return try {
            val native = readPage(json.readTree(response.body)) ?: return DirectorySearch.Unavailable
            when {
                native.total >= SEARCH_LIMIT -> DirectorySearch.RefineSearch
                native.start != null && native.start != requested.offset -> DirectorySearch.Unavailable
                !native.matches(requested, excluding) -> DirectorySearch.Unavailable
                else -> DirectorySearch.Found(UserPage(native.total.toInt(), native.users))
            }
        } catch (_: JacksonException) {
            DirectorySearch.Unavailable
        }
    }

    fun member(
        response: ManagementResponse,
        id: String,
    ): DirectoryMembership {
        if (response.status == HttpStatus.NOT_FOUND.value()) return DirectoryMembership.ABSENT
        if (response.status != HttpStatus.OK.value() || !response.json) return DirectoryMembership.UNAVAILABLE
        return try {
            val received =
                json
                    .readTree(response.body)
                    ?.get("user_id")
                    ?.takeIf { it.isString }
                    ?.asString()
            if (received == id) DirectoryMembership.PRESENT else DirectoryMembership.UNAVAILABLE
        } catch (_: JacksonException) {
            DirectoryMembership.UNAVAILABLE
        }
    }

    private fun readPage(root: JsonNode?): NativePage? {
        if (root?.isObject != true) return null
        val total = root.get("total").nonnegativeInteger() ?: return null
        val nodes = root.get("users")?.takeIf { it.isArray }?.values() ?: return null
        val users = nodes.mapNotNull(::user)
        val start = root.get("start")
        if (start != null && start.nonnegativeInteger() == null) return null
        if (users.size != nodes.size) return null
        return NativePage(total, users, start?.asLong())
    }

    private fun user(node: JsonNode): DirectoryUser? {
        val id =
            node
                .get("user_id")
                ?.takeIf { it.isString }
                ?.asString()
                ?.takeIf(String::isNotBlank)
        val name =
            node
                .get("name")
                ?.takeIf { it.isString }
                ?.asString()
                ?.takeIf(String::isNotBlank)
        return if (id != null && name != null) DirectoryUser(id, name) else null
    }
}

private fun JsonNode?.nonnegativeInteger(): Long? =
    this?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()?.takeIf { it >= 0 }
