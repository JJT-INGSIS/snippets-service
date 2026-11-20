package com.jjt.ingsis.snippets.telemetry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerMapping

private const val NGINX_ID = "0123456789abcdef0123456789abcdef"

private class RecordedAttributes : TransactionAttributes {
    val values = mutableMapOf<String, String>()
    var ignored = false

    override fun add(
        name: String,
        value: String,
    ) {
        values[name] = value
    }

    override fun ignoreCurrent() {
        ignored = true
    }
}

class RequestTelemetryFilterTest {
    private val attributes = RecordedAttributes()
    private val filter = RequestTelemetryFilter(attributes)

    private fun request(
        uri: String,
        route: String?,
        requestId: String? = null,
    ) = MockHttpServletRequest("POST", uri).apply {
        requestId?.let { addHeader(REQUEST_ID_HEADER, it) }
        route?.let { setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, it) }
    }

    private fun run(
        request: MockHttpServletRequest,
        status: Int,
        during: () -> Unit = {},
    ) {
        val response = MockHttpServletResponse()
        val chain =
            MockFilterChain(
                object : jakarta.servlet.http.HttpServlet() {
                    override fun service(
                        req: jakarta.servlet.ServletRequest,
                        res: jakarta.servlet.ServletResponse,
                    ) {
                        during()
                        (res as jakarta.servlet.http.HttpServletResponse).status = status
                    }
                },
            )
        filter.doFilter(request, response, chain)
    }

    @Test
    fun `the nginx request id reaches logs during the request and the transaction`() {
        var seen: String? = null

        run(request("/snippets", "/snippets", NGINX_ID), 201) { seen = MDC.get(REQUEST_ID) }

        assertThat(seen).isEqualTo(NGINX_ID)
        assertThat(attributes.values).containsEntry(REQUEST_ID, NGINX_ID).containsEntry(OUTCOME, "success")
    }

    @Test
    fun `a request id that nginx did not generate is ignored`() {
        listOf("attacker-value", NGINX_ID.uppercase(), NGINX_ID + "0", "").forEach { header ->
            var seen: String? = "unset"

            run(request("/snippets", "/snippets", header), 201) { seen = MDC.get(REQUEST_ID) }

            assertThat(seen).describedAs(header).isNull()
            assertThat(attributes.values).doesNotContainKey(REQUEST_ID)
        }
    }

    @Test
    fun `the outcome follows the response and the context is cleared afterwards`() {
        run(request("/snippets", "/snippets", NGINX_ID), 502)

        assertThat(attributes.values).containsEntry(OUTCOME, "technical")
        TELEMETRY_KEYS.forEach { assertThat(MDC.get(it)).describedAs(it).isNull() }
    }

    @Test
    fun `the healthcheck probe is not recorded even when the catch-all resource route answers it`() {
        listOf(null, "/**").forEach { route ->
            val recorded = RecordedAttributes()

            RequestTelemetryFilter(recorded).doFilter(request("/", route), MockHttpServletResponse(), MockFilterChain())

            assertThat(recorded.ignored).describedAs(route ?: "no route").isTrue()
            assertThat(recorded.values).doesNotContainKey(OUTCOME)
        }
    }

    @Test
    fun `other unmatched paths are kept but carry no outcome`() {
        run(request("/unknown", null), 404)

        assertThat(attributes.ignored).isFalse()
        assertThat(attributes.values).doesNotContainKey(OUTCOME)
    }
}
