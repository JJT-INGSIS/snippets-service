package com.jjt.ingsis.snippets.users

import com.jjt.ingsis.snippets.metadata.PageRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

private val firstPage = PageRequest(0, 10)

private fun directory(vararg users: DirectoryUser): ConfiguredUserDirectory =
    ConfiguredUserDirectory(DevelopmentUsers(users.toList()))

class ConfiguredUserDirectoryTest {
    private val team =
        directory(
            DirectoryUser("dev-thiago", "Thiago"),
            DirectoryUser("dev-juan", "Juan"),
            DirectoryUser("dev-ana", "Ana"),
            DirectoryUser("dev-juanfran", "Juan Francisco"),
            DirectoryUser("dev-bruno", "Bruno"),
        )

    @Test
    fun `part of a name finds only matching users ignoring case`() {
        val found = team.search("AN", excluding = "nobody", page = firstPage)

        assertThat(found.users.map { it.id }).containsExactly("dev-ana", "dev-juan", "dev-juanfran")
        assertThat(found.count).isEqualTo(3)
    }

    @Test
    fun `the requester never appears among the recipients`() {
        val found = team.search("", excluding = "dev-juan", page = firstPage)

        assertThat(found.users.map { it.id }).containsExactly("dev-ana", "dev-bruno", "dev-juanfran", "dev-thiago")
    }

    @Test
    fun `order is by name ignoring case then by id`() {
        val twins =
            directory(
                DirectoryUser("dev-b", "sam"),
                DirectoryUser("dev-a", "Sam"),
                DirectoryUser("dev-c", "alex"),
            )

        val found = twins.search("", excluding = "nobody", page = firstPage)

        assertThat(found.users.map { it.id }).containsExactly("dev-c", "dev-a", "dev-b")
    }

    @Test
    fun `pages split the ordered result and keep the total count`() {
        val pages = (0..3).map { number -> team.search("", excluding = "nobody", page = PageRequest(number, 2)) }

        assertThat(pages.map { it.count }).containsOnly(5)
        assertThat(pages.flatMap { it.users }.map { it.name })
            .containsExactly("Ana", "Bruno", "Juan", "Juan Francisco", "Thiago")
        assertThat(pages.last().users).isEmpty()
        assertThat(team.search("", "nobody", PageRequest(Int.MAX_VALUE, 100)).users).isEmpty()
    }

    @Test
    fun `no matches is an empty page with zero count`() {
        assertThat(team.search("zzz", excluding = "nobody", page = firstPage)).isEqualTo(UserPage(0, emptyList()))
    }

    @Test
    fun `only configured ids are known recipients`() {
        assertThat(team.contains("dev-ana")).isTrue()
        assertThat(team.contains("DEV-ANA")).isFalse()
        assertThat(team.contains("dev-unknown")).isFalse()
        assertThat(directory().contains("dev-ana")).isFalse()
    }

    @Test
    fun `a broken catalogue stops the service instead of guessing`() {
        assertThatThrownBy { DevelopmentUsers(listOf(DirectoryUser(" ", "Ana"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DevelopmentUsers(listOf(DirectoryUser("dev-ana", ""))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            DevelopmentUsers(listOf(DirectoryUser("dev-ana", "Ana"), DirectoryUser("dev-ana", "Other")))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
