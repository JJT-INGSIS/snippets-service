package com.jjt.ingsis.snippets.reformatting

import com.jjt.ingsis.snippets.http.StubRequest
import com.jjt.ingsis.snippets.http.StubResponse
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap

// Contract fixture only. Redis persistence/restart is exercised against the real provider separately.
internal class OperationHttpFixture(
    private val revision: String,
    private val formatting: (StubRequest) -> StubResponse,
) {
    private val json = JsonMapper.builder().build()
    private val operations = ConcurrentHashMap<String, String>()
    private val headers = mapOf("Execution-Active-Limit" to "4")

    fun answer(request: StubRequest): StubResponse? {
        if (request.path == "/processor") {
            return StubResponse(
                200,
                """{"capabilities":{"format":"$revision"}}""",
            )
        }
        if (!request.path.startsWith("/operations/")) return null
        val id = request.path.removePrefix("/operations/").substringBefore('/')
        if (request.method == "GET") {
            return operations[id]?.let { StubResponse(200, it, headers = headers) }
                ?: StubResponse(404, "{}")
        }
        if (request.path.endsWith("/cancel")) {
            val fence = """{"id":"$id","command":null,"state":"CANCELLED","reason":"START_FENCED"}"""
            return StubResponse(200, operations.computeIfAbsent(id) { fence }, headers = headers)
        }
        val response = formatting(request.copy(path = "/formatting"))
        if (response.status != 200) return response
        val body = json.readTree(response.body)
        val successful = body.get("formatted").asBoolean()
        val output =
            mapOf(
                "outcome" to if (successful) "FORMATTED" else "INVALID_CODE",
                "code" to if (successful) body.get("code").asString() else null,
                "diagnostics" to body.get("diagnostics"),
            )
        val record =
            json.writeValueAsString(
                mapOf(
                    "id" to id,
                    "command" to json.readTree(request.body),
                    "state" to "COMPLETED",
                    "output" to output,
                ),
            )
        return StubResponse(200, operations.computeIfAbsent(id) { record }, headers = headers)
    }
}
