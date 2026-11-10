package com.jjt.ingsis.snippets.users.auth0

import com.jjt.ingsis.snippets.http.security.AuthProperties
import com.jjt.ingsis.snippets.metadata.PageRequest
import com.jjt.ingsis.snippets.users.ConfiguredUserDirectory
import com.jjt.ingsis.snippets.users.DevelopmentUsers
import com.jjt.ingsis.snippets.users.DirectoryMembership
import com.jjt.ingsis.snippets.users.DirectorySearch
import com.jjt.ingsis.snippets.users.DirectoryUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DirectoryConfigurationTest {
    private val configured = DevelopmentUsers(listOf(DirectoryUser("dev-ana", "Ana")))
    private val factory = DirectoryConfiguration()

    @Test fun `dev mode keeps its configured directory without using service credentials`() {
        val directory = factory.userDirectory(AuthProperties(mode = "dev"), configured, Auth0DirectoryProperties())
        assertThat(directory).isInstanceOf(ConfiguredUserDirectory::class.java)
        assertThat(directory.contains("dev-ana")).isEqualTo(DirectoryMembership.PRESENT)
    }

    @Test fun `auth0 without its read credential starts with explicit unavailability never dev fallback`() {
        val directory = factory.userDirectory(AuthProperties(mode = "auth0"), configured, Auth0DirectoryProperties())
        assertThat(directory.search("", "auth0|alice", PageRequest(0, 10))).isEqualTo(DirectorySearch.Unavailable)
        assertThat(directory.contains("dev-ana")).isEqualTo(DirectoryMembership.UNAVAILABLE)
    }

    @Test fun `disabled mode does not expose the development directory`() {
        val directory = factory.userDirectory(AuthProperties(mode = "disabled"), configured, Auth0DirectoryProperties())
        assertThat(directory.contains("dev-ana")).isEqualTo(DirectoryMembership.UNAVAILABLE)
    }

    @Test fun `configured auth0 uses its issuer and the remote adapter without a startup network request`() {
        val directory =
            factory.userDirectory(
                auth = AuthProperties(mode = "auth0", issuer = "https://tenant.fixture/"),
                development = configured,
                properties = Auth0DirectoryProperties(clientId = "read-client", clientSecret = "read-secret"),
            )
        assertThat(directory).isInstanceOf(Auth0UserDirectory::class.java)
    }

    @Test fun `insecure Management origins cannot receive the credential`() {
        val directory =
            factory.userDirectory(
                auth = AuthProperties(mode = "auth0", issuer = "http://tenant.fixture/"),
                development = configured,
                properties = Auth0DirectoryProperties(clientId = "read-client", clientSecret = "read-secret"),
            )
        assertThat(directory.contains("auth0|bob")).isEqualTo(DirectoryMembership.UNAVAILABLE)
    }
}
