package com.jjt.ingsis.snippets.http

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

internal const val ACTOR = "dev-juan"

internal val json: JsonMapper = JsonMapper.builder().build()

internal data class HttpAnswer(
    val status: Int,
    val contentType: String?,
    val body: JsonNode,
) {
    val id: UUID
        get() = UUID.fromString(body.get("id").asString())

    val type: String?
        get() = body.get("type")?.asString()
}

internal fun requestBody(
    code: String = "println(1);",
    name: String = "Example",
    language: String = "printscript",
    version: String = "1.0",
): String =
    json.writeValueAsString(
        mapOf("name" to name, "description" to null, "language" to language, "version" to version, "code" to code),
    )

internal class SnippetsApi(
    private val port: Int,
) {
    private val client = HttpClient.newHttpClient()

    fun create(
        body: String,
        keys: List<String> = listOf(UUID.randomUUID().toString()),
        actors: List<String> = listOf(ACTOR),
        contentType: String = "application/json",
    ): HttpAnswer {
        val request = request("/snippets", contentType, actors).POST(HttpRequest.BodyPublishers.ofString(body))
        keys.forEach { key -> request.header("Idempotency-Key", key) }

        return send(request)
    }

    fun update(
        snippetId: String,
        body: String,
        actors: List<String> = listOf(ACTOR),
        contentType: String = "application/json",
    ): HttpAnswer {
        val request = request("/snippets/$snippetId", contentType, actors)

        return send(request.PUT(HttpRequest.BodyPublishers.ofString(body)))
    }

    fun list(
        query: String,
        actors: List<String> = listOf(ACTOR),
    ): HttpAnswer = send(request("/snippets$query", "application/json", actors).GET())

    fun users(
        query: String,
        actors: List<String> = listOf(ACTOR),
    ): HttpAnswer = send(request("/users$query", "application/json", actors).GET())

    private fun request(
        path: String,
        contentType: String,
        actors: List<String>,
    ): HttpRequest.Builder {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).header("Content-Type", contentType)
        actors.forEach { actor -> request.header("X-Dev-Actor-Id", actor) }

        return request
    }

    private fun send(request: HttpRequest.Builder): HttpAnswer {
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())

        return HttpAnswer(
            status = response.statusCode(),
            contentType = response.headers().firstValue("Content-Type").orElse(null),
            body = json.readTree(response.body()),
        )
    }
}
