package com.jjt.ingsis.snippets.telemetry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class RequestOutcomeTest {
    @ParameterizedTest
    @CsvSource(
        "200, success",
        "201, success",
        "308, success",
        "400, invalid-request",
        "401, denied",
        "403, denied",
        "404, not-found",
        "409, conflict",
        "422, rejected",
        "413, client-error",
        "500, technical",
        "502, technical",
        "504, technical",
    )
    fun `client mistakes and denials are told apart from technical failures`(
        status: Int,
        label: String,
    ) {
        assertThat(RequestOutcome.of(status).label).isEqualTo(label)
    }
}
