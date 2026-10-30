package com.jjt.ingsis.snippets.execution

sealed interface RunResult<out T> {
    data class Success<T>(
        val value: T,
    ) : RunResult<T>

    data class Refused(
        val failure: RunFailure,
    ) : RunResult<Nothing>
}

enum class RunFailure(
    val category: String,
) {
    IDENTITY_REQUIRED("identity-required"),
    INVALID_REQUEST("invalid-request"),
    SNIPPET_MISSING("snippet-not-found"),
    TEST_MISSING("test-not-found"),
    EXECUTION_MISSING("execution-not-found"),
    ACTOR_MISMATCH("execution-actor-mismatch"),
    ACTIVE("execution-already-active"),
    SNAPSHOT_CONFLICT("snapshot-conflict"),
    HISTORY_UNAVAILABLE("event-history-unavailable"),
    CONSUMER_CONFLICT("execution-consumer-conflict"),
    INVALID_CURSOR("invalid-cursor"),
    CAPACITY_EXHAUSTED("execution-capacity-exhausted"),
    UNSUPPORTED_CAPABILITY("unsupported-capability"),
    UNSUPPORTED_VERSION("unsupported-version"),
    INVALID_CODE("invalid-code"),
    PAYLOAD_TOO_LARGE("payload-too-large"),
    DEPENDENCY_UNAVAILABLE("dependency-unavailable"),
    DEPENDENCY_TIMEOUT("dependency-timeout"),
}
