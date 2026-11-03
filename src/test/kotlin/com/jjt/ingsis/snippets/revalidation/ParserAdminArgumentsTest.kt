package com.jjt.ingsis.snippets.revalidation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class ParserAdminArgumentsTest {
    @Test
    fun `activation requires a request scope revision and reason and cannot supply an administrator`() {
        val request = UUID.randomUUID()
        val args =
            listOf(
                "activate",
                "--request-id",
                request.toString(),
                "--language",
                "printscript",
                "--version",
                "1.1",
                "--processor",
                "sha256-v1:" + "a".repeat(64),
                "--reason",
                "reviewed parser fix",
            )
        val parsed = ParserAdminArguments.parse(args) as ParserAdminArguments.Activate
        assertThat(parsed.request.requestId).isEqualTo(request)
        assertThatThrownBy { ParserAdminArguments.parse(args + listOf("--operator", "admin")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ParserAdminArguments.parse(args + listOf("--version", "1.0")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `status accepts only one job identifier and an invalid revision never becomes a language version`() {
        val job = UUID.randomUUID()
        assertThat(ParserAdminArguments.parse(listOf("status", "--job-id", job.toString())))
            .isEqualTo(ParserAdminArguments.Status(job))
        assertThatThrownBy { ParserAdminArguments.parse(listOf("status", "--job-id", job.toString(), "--extra")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            ParserAdminArguments.parse(
                listOf(
                    "activate",
                    "--request-id",
                    UUID.randomUUID().toString(),
                    "--language",
                    "printscript",
                    "--version",
                    "1.1",
                    "--processor",
                    "1.1",
                    "--reason",
                    "fix",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
