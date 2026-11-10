package com.jjt.ingsis.snippets.users.auth0

import com.jjt.ingsis.snippets.metadata.PageRequest
import com.jjt.ingsis.snippets.users.DirectoryMembership
import com.jjt.ingsis.snippets.users.DirectorySearch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

private class AdjustableClock : Clock() {
    val now = AtomicReference(Instant.parse("2026-11-08T12:00:00Z"))

    override fun instant(): Instant = now.get()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

class ManagementTokensTest {
    private val fixture = ManagementFixture()

    @AfterEach fun close() {
        fixture.close()
    }

    @Test fun `the service token is reused until its safe expiry and is never a user bearer`() {
        val clock = AdjustableClock()
        val directory = fixture.directory(clock)
        directory.contains("auth0|bob")
        directory.search("B", "auth0|alice", PageRequest(0, 10))
        assertThat(fixture.requests.count { it.path == "/oauth/token" }).isEqualTo(1)
        assertThat(fixture.requests.filter { it.path != "/oauth/token" }.map { it.authorization })
            .containsOnly("Bearer management-token")
        clock.now.updateAndGet { it.plusSeconds(580) }
        directory.contains("auth0|bob")
        assertThat(fixture.requests.count { it.path == "/oauth/token" }).isEqualTo(2)
    }

    @Test fun `a rejected service token is invalidated without retrying the current operation`() {
        val directory = fixture.directory()
        fixture.memberResponse = DirectoryResponse(401, "{}")
        assertThat(directory.contains("auth0|bob")).isEqualTo(DirectoryMembership.UNAVAILABLE)
        assertThat(fixture.requests).hasSize(2)
        fixture.memberResponse = DirectoryResponse(200, """{"user_id":"auth0|bob"}""")
        assertThat(directory.contains("auth0|bob")).isEqualTo(DirectoryMembership.PRESENT)
        assertThat(fixture.requests.count { it.path == "/oauth/token" }).isEqualTo(2)
    }

    @Test fun `invalid failed or overprivileged token grants cannot reach Management endpoints`() {
        listOf(
            "not json",
            "{}",
            """{"access_token":"x","token_type":"Bearer","expires_in":600,"scope":"read:users create:users"}""",
            """{"access_token":"x","token_type":"Bearer","expires_in":0,"scope":"read:users"}""",
            """{"access_token":"x","token_type":"Basic","expires_in":600,"scope":"read:users"}""",
            """{"access_token":"x","token_type":"Bearer","expires_in":600}""",
            """{"access_token":"x","token_type":"Bearer","expires_in":18446744073709551676,"scope":"read:users"}""",
        ).forEach { body ->
            fixture.tokenResponse = DirectoryResponse(200, body)
            assertThat(
                fixture.directory().search("B", "auth0|alice", PageRequest(0, 10)),
            ).isEqualTo(DirectorySearch.Unavailable)
        }
        fixture.tokenResponse = DirectoryResponse(401, """{"error":"invalid_client"}""")
        assertThat(fixture.directory().contains("auth0|bob")).isEqualTo(DirectoryMembership.UNAVAILABLE)
        assertThat(fixture.requests.map { it.path }).containsOnly("/oauth/token")
    }

    @Test fun `credentials and cached token diagnostics do not expose secret values`() {
        assertThat(ManagementCredential("client", "fixture-secret").toString()).doesNotContain("fixture-secret")
        assertThat(ManagementAccess.Granted("secret-token", Instant.MAX).toString()).doesNotContain("secret-token")
        assertThat(
            Auth0DirectoryProperties(clientSecret = "fixture-secret").toString(),
        ).doesNotContain("fixture-secret")
    }

    @Test
    fun `a connection failure is explicit unavailability rather than absence`() {
        val stopped = ManagementFixture()
        val directory = stopped.directory()
        stopped.close()
        assertThat(directory.contains("auth0|bob")).isEqualTo(DirectoryMembership.UNAVAILABLE)
    }
}
