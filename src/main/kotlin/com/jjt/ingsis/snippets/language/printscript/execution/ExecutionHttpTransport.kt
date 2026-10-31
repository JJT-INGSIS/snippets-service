package com.jjt.ingsis.snippets.language.printscript.execution

import com.jjt.ingsis.snippets.language.PROCESSOR_REVISION_HEADER
import com.jjt.ingsis.snippets.language.ProcessorIdentity
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.language.execution.ExecutionResult
import com.jjt.ingsis.snippets.language.printscript.PrintScriptProperties
import org.springframework.http.HttpStatus
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse

internal class ExecutionHttpTransport(
    private val properties: PrintScriptProperties,
    val mapper: ObjectMapper,
) {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(
                properties.connectTimeout,
            ).followRedirects(HttpClient.Redirect.NEVER)
            .build()

    fun <T> json(
        method: String,
        path: String,
        body: Any?,
        expected: Int,
        decode: (JsonNode, ProcessorRevision?) -> T,
    ): ExecutionResult<T> =
        attempt(method != "GET") {
            val response = send(method, path, body)
            timedExecutionBody(response.body(), properties.readTimeout) { input ->
                val bytes = input.readNBytes(MAX_JSON_BYTES + 1)
                if (bytes.size > MAX_JSON_BYTES) throw ExecutionProtocolFailure()
                val node = mapper.readTree(bytes) ?: throw ExecutionProtocolFailure()
                if (response.statusCode() !=
                    expected
                ) {
                    rejection(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""), node)
                } else {
                    if (!response
                            .headers()
                            .firstValue(
                                "Content-Type",
                            ).orElse("")
                            .substringBefore(';')
                            .trim()
                            .equals("application/json")
                    ) {
                        throw ExecutionProtocolFailure()
                    }
                    ExecutionResult.Success(decode(node, processorOf(response)))
                }
            }
        }

    fun processorOf(response: HttpResponse<*>): ProcessorRevision? =
        ProcessorIdentity.of(response.headers().firstValue(PROCESSOR_REVISION_HEADER).orElse(null)).verifiedOrNull()

    fun send(
        method: String,
        path: String,
        body: Any?,
    ): HttpResponse<java.io.InputStream> {
        val request =
            HttpRequest
                .newBuilder(URI.create(properties.baseUrl.trimEnd('/') + path))
                .timeout(properties.readTimeout)
                .header("Accept", "application/json, application/x-ndjson, application/problem+json")
        if (body ==
            null
        ) {
            request.GET()
        } else {
            request
                .header(
                    "Content-Type",
                    "application/json",
                ).method(method, HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
    }

    fun <T> rejection(
        status: Int,
        contentType: String,
        node: JsonNode,
    ): ExecutionResult<T> {
        if (contentType.substringBefore(';').trim() != "application/problem+json" ||
            node.path("status").asInt() != status
        ) {
            throw ExecutionProtocolFailure()
        }
        val type = ExecutionProtocol.text(node, "type")
        if (!type.startsWith("urn:printscript:problem:")) throw ExecutionProtocolFailure()
        val rejections =
            setOf(
                HttpStatus.BAD_REQUEST,
                HttpStatus.NOT_FOUND,
                HttpStatus.CONFLICT,
                HttpStatus.UNPROCESSABLE_CONTENT,
                HttpStatus.TOO_MANY_REQUESTS,
            ).map { it.value() }
        return if (status in rejections) {
            ExecutionResult.Rejected(type.substringAfterLast(':'))
        } else {
            ExecutionResult.Failed("execution-service-failure", uncertain = true)
        }
    }

    fun <T> attempt(
        uncertain: Boolean = false,
        action: () -> ExecutionResult<T>,
    ): ExecutionResult<T> =
        try {
            action()
        } catch (_: ConnectException) {
            ExecutionResult.Failed("execution-service-unavailable")
        } catch (_: HttpConnectTimeoutException) {
            ExecutionResult.Failed("execution-service-unavailable")
        } catch (_: IOException) {
            ExecutionResult.Failed("execution-service-unavailable", uncertain)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ExecutionResult.Failed("execution-transport-interrupted", uncertain)
        } catch (_: JacksonException) {
            ExecutionResult.Failed("invalid-execution-response", uncertain)
        } catch (_: ExecutionProtocolFailure) {
            ExecutionResult.Failed("invalid-execution-response", uncertain)
        }

    companion object {
        const val MAX_JSON_BYTES = 65_536
    }
}
