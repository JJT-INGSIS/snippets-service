package com.jjt.ingsis.snippets.http

// Existing listing/detail contract tests isolate authorization/storage from the remote lint catalog.
// Real-linter acceptance remains separate; no invented rule is exposed by production.
internal fun verifiedLintCatalog(): ScriptedHttpService =
    ScriptedHttpService { request ->
        if (request.path == "/linting/rules") {
            val version = request.query?.substringAfter("version=") ?: "1.1"
            StubResponse(
                status = 200,
                body = """{"version":"$version","rules":[]}""",
                headers = mapOf("Processor-Revision" to "sha256-v1:" + "a".repeat(64)),
            )
        } else {
            StubResponse(404, "{}")
        }
    }
