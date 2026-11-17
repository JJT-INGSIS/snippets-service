package com.jjt.ingsis.snippets.telemetry

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import java.util.concurrent.TimeUnit

private const val HEALTHCHECK_PATH = "/"

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestTelemetryFilter(
    private val attributes: TransactionAttributes,
) : OncePerRequestFilter() {
    private val logger = LoggerFactory.getLogger(RequestTelemetryFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val started = System.nanoTime()
        acceptedRequestId(request.getHeader(REQUEST_ID_HEADER))?.let { requestId ->
            MDC.put(REQUEST_ID, requestId)
            attributes.add(REQUEST_ID, requestId)
        }
        try {
            chain.doFilter(request, response)
        } finally {
            if (!request.isAsyncStarted) {
                complete(request, response.status, started)
            }
            TELEMETRY_KEYS.forEach(MDC::remove)
        }
    }

    private fun complete(
        request: HttpServletRequest,
        status: Int,
        started: Long,
    ) {
        val route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
        if (route == null) {
            if (request.requestURI == HEALTHCHECK_PATH) attributes.ignoreCurrent()
            return
        }
        val outcome = RequestOutcome.of(status).label
        attributes.add(OUTCOME, outcome)
        MDC.put(OPERATION, request.method + " " + route)
        MDC.put(OUTCOME, outcome)
        MDC.put(DURATION_MS, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).toString())
        logger.info("Request completed with status {}", status)
    }
}
