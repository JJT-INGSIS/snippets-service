package com.jjt.ingsis.snippets.telemetry

private const val FIRST_CLIENT_ERROR = 400
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404
private const val CONFLICT = 409
private const val UNPROCESSABLE = 422
private const val FIRST_SERVER_ERROR = 500

enum class RequestOutcome(
    val label: String,
) {
    SUCCESS("success"),
    INVALID_REQUEST("invalid-request"),
    DENIED("denied"),
    NOT_FOUND_RESOURCE("not-found"),
    CONFLICTED("conflict"),
    REJECTED("rejected"),
    CLIENT_ERROR("client-error"),
    TECHNICAL("technical"),
    ;

    companion object {
        fun of(status: Int): RequestOutcome =
            when {
                status < FIRST_CLIENT_ERROR -> SUCCESS
                status >= FIRST_SERVER_ERROR -> TECHNICAL
                status == FIRST_CLIENT_ERROR -> INVALID_REQUEST
                status == UNAUTHORIZED || status == FORBIDDEN -> DENIED
                status == NOT_FOUND -> NOT_FOUND_RESOURCE
                status == CONFLICT -> CONFLICTED
                status == UNPROCESSABLE -> REJECTED
                else -> CLIENT_ERROR
            }
    }
}
