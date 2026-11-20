package com.jjt.ingsis.snippets.language.printscript

import com.jjt.ingsis.snippets.language.ProcessorIdentity
import com.jjt.ingsis.snippets.language.linting.LintCatalogResult
import com.jjt.ingsis.snippets.language.linting.LintResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@EnabledIfEnvironmentVariable(named = "PRINTSCRIPT_LINT_URL", matches = ".+")
class PrintScriptLintLiveTest {
    private val base = System.getenv("PRINTSCRIPT_LINT_URL").trimEnd('/')
    private val client = PrintScriptLinter(PrintScriptProperties(baseUrl = base))

    @Test
    fun `real jar stamps lint and catalog with the capability advertised by processor`() {
        val manifest =
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("$base/processor")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertThat(manifest.statusCode()).isEqualTo(200)
        val revision =
            JsonMapper
                .builder()
                .build()
                .readTree(
                    manifest.body(),
                ).path("capabilities")
                .path("lint")
                .asString()
        val catalog = client.catalog("1.1")
        assertThat(catalog.result).isInstanceOf(LintCatalogResult.Catalog::class.java)
        assertThat((catalog.processor as ProcessorIdentity.Verified).revision.value).isEqualTo(revision)
        val empty = client.lint("1.1", "println(1+2);", emptyMap())
        assertThat((empty.result as LintResult.Analyzed).compliant).isTrue()
        val enabled =
            client.lint(
                "1.1",
                "println(1+2);",
                mapOf("mandatory-variable-or-literal-in-println" to true),
            )
        assertThat((enabled.result as LintResult.Analyzed).compliant).isFalse()
        assertThat(enabled.processor).isEqualTo(catalog.processor)
    }

    @Test
    fun `real provider keeps invalid code and invalid choice separate`() {
        assertThat(client.lint("1.0", "let = ;", emptyMap()).result)
            .isInstanceOf(LintResult.InvalidCode::class.java)
        assertThat(client.lint("1.0", "println(1);", mapOf("identifier_format" to "invented")).result)
            .isInstanceOf(LintResult.InvalidRules::class.java)
    }
}
