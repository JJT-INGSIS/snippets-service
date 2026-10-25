package com.jjt.ingsis.snippets.users

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("development")
data class DevelopmentUsers(
    val users: List<DirectoryUser> = emptyList(),
) {
    init {
        require(users.none { it.id.isBlank() || it.name.isBlank() }) {
            "Every development user needs a non-blank id and name"
        }
        require(users.map { it.id }.toSet().size == users.size) { "Development user ids must be unique" }
    }
}
