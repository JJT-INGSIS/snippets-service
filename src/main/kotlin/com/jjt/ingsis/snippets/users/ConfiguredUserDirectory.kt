package com.jjt.ingsis.snippets.users

import com.jjt.ingsis.snippets.metadata.PageRequest
import org.springframework.stereotype.Component

@Component
class ConfiguredUserDirectory(
    configured: DevelopmentUsers,
) : UserDirectory {
    private val users =
        configured.users.sortedWith(compareBy({ it.name.lowercase() }, { it.id.lowercase() }, { it.id }))

    override fun search(
        name: String,
        excluding: String,
        page: PageRequest,
    ): UserPage {
        val matching = users.filter { it.id != excluding && it.name.contains(name, ignoreCase = true) }
        val skipped = page.offset.coerceAtMost(matching.size.toLong()).toInt()

        return UserPage(matching.size, matching.drop(skipped).take(page.size))
    }

    override fun contains(id: String): Boolean = users.any { it.id == id }
}
