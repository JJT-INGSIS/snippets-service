package com.jjt.ingsis.snippets.language.printscript

import com.jjt.ingsis.snippets.language.PROCESSOR_REVISION_HEADER
import com.jjt.ingsis.snippets.language.ProcessorIdentity
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.language.linting.LintCatalogResult
import com.jjt.ingsis.snippets.language.linting.LintCheckResult
import com.jjt.ingsis.snippets.language.linting.LintResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class PrintScriptLinterTest {
    private val revision = "sha256-v1:" + "a".repeat(64)
    private val analyzed = """{"analyzed":true,"compliant":true,"diagnostics":[]}"""
    private val finding = """{"rule":"identifier_format","message":"Nombre inválido","line":2,"column":5}"""

    @Test
    fun `provider defaults retain their actual values and attribution without local fallback`() {
        val body = """{"version":"1.1","rules":[],"defaults":{"identifier_format":"camel case","selected":true}}"""
        StubLanguageService(200, body, headers = mapOf(PROCESSOR_REVISION_HEADER to revision)).use {
            val answer = linter(it.baseUrl).catalog("1.1")
            val catalog = answer.result as LintCatalogResult.Catalog
            assertThat(catalog.defaults).isEqualTo(mapOf("identifier_format" to "camel case", "selected" to true))
            assertThat(answer.processor).isEqualTo(ProcessorIdentity.Verified(ProcessorRevision(revision)))
        }
        StubLanguageService(200, """{"version":"1.1","rules":[]}""").use {
            assertThat((linter(it.baseUrl).catalog("1.1").result as LintCatalogResult.Catalog).defaults).isNull()
        }
    }

    @Test
    fun `empty selection crosses HTTP unchanged with operation revision`() {
        StubLanguageService(200, analyzed, headers = mapOf(PROCESSOR_REVISION_HEADER to revision)).use {
            val result = linter(it.baseUrl).lint("1.0", "println(1+2);", emptyMap())
            assertThat((result.result as LintResult.Analyzed).compliant).isTrue()
            assertThat(result.processor).isEqualTo(ProcessorIdentity.Verified(ProcessorRevision(revision)))
            assertThat(it.received.single().body).contains("\"rules\":{}")
            assertThat(it.received.single().path).isEqualTo("/linting")
        }
    }

    @Test
    fun `noncompliance differs from lexical or syntactic errors`() {
        assertThat(lint(200, """{"analyzed":true,"compliant":false,"diagnostics":[$finding]}"""))
            .isInstanceOf(LintResult.Analyzed::class.java)
        assertThat(lint(200, """{"analyzed":false,"compliant":null,"diagnostics":[$finding]}"""))
            .isInstanceOf(LintResult.InvalidCode::class.java)
        assertThat(lint(200, """{"analyzed":true,"compliant":true,"diagnostics":[$finding]}"""))
            .isInstanceOf(LintResult.Failed::class.java)
    }

    @Test
    fun `invalid rules unsupported versions and technical failures stay separate`() {
        assertThat(
            lint(
                422,
                """{"type":"urn:printscript:problem:invalid-linting-rules",
            "violations":[{"rule":"identifier_format","reason":"unknown-choice"}]}""",
            ),
        ).isInstanceOf(LintResult.InvalidRules::class.java)
        assertThat(lint(422, """{"supportedVersions":["1.0","1.1"]}"""))
            .isInstanceOf(LintResult.UnsupportedVersion::class.java)
        assertThat(lint(500, """{"title":"unavailable"}""")).isInstanceOf(LintResult.Failed::class.java)
        assertThat(lint(200, "not-json")).isInstanceOf(LintResult.Failed::class.java)
        assertThat(lint(400, """{"type":"urn:printscript:problem:request-too-large"}"""))
            .isEqualTo(LintResult.CodeTooLarge)
    }

    @Test
    fun `choice options and nullability survive without adapter interpretation`() {
        val body = """{"version":"1.1","rules":[{"id":"identifier_format","type":"choice",
            "acceptsNull":true,"choices":["first","second"],"conflictsWith":[]}]}"""
        StubLanguageService(200, body).use {
            val result = linter(it.baseUrl).catalog("1.1").result as LintCatalogResult.Catalog
            assertThat(result.rules.single().choices).containsExactly("first", "second")
            assertThat(result.rules.single().acceptsNull).isTrue()
            assertThat(result.rules.single().type).isEqualTo("choice")
        }
        StubLanguageService(200, """{"valid":true,"violations":[]}""").use {
            val result = linter(it.baseUrl).check("1.1", mapOf("identifier_format" to null))
            assertThat((result.result as LintCheckResult.Checked).valid).isTrue()
            assertThat(it.received.single().body).contains("\"identifier_format\":null")
        }
    }

    @Test
    fun `missing and malformed revisions are never invented`() {
        StubLanguageService(200, analyzed).use {
            assertThat(linter(it.baseUrl).lint("1.0", "println(1);", emptyMap()).processor)
                .isEqualTo(ProcessorIdentity.Missing)
        }
        StubLanguageService(200, analyzed, headers = mapOf(PROCESSOR_REVISION_HEADER to "1.1")).use {
            assertThat(linter(it.baseUrl).lint("1.0", "println(1);", emptyMap()).processor)
                .isInstanceOf(ProcessorIdentity.Malformed::class.java)
        }
    }

    @Test
    fun `timeout and provider down are technical failures`() {
        StubLanguageService(200, analyzed, delay = Duration.ofMillis(200)).use {
            val client =
                PrintScriptLinter(PrintScriptProperties(baseUrl = it.baseUrl, readTimeout = Duration.ofMillis(30)))
            assertThat(client.lint("1.0", "println(1);", emptyMap()).result).isInstanceOf(LintResult.Failed::class.java)
        }
        val url = StubLanguageService(200, analyzed).use { it.baseUrl }
        assertThat(
            linter(url).lint("1.0", "println(1);", emptyMap()).result,
        ).isInstanceOf(LintResult.Failed::class.java)
    }

    private fun lint(
        status: Int,
        body: String,
    ): LintResult =
        StubLanguageService(status, body).use {
            linter(it.baseUrl).lint("1.0", "println(1);", emptyMap()).result
        }

    private fun linter(url: String): PrintScriptLinter = PrintScriptLinter(PrintScriptProperties(baseUrl = url))
}
