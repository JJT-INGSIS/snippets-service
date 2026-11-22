package com.jjt.ingsis.snippets.identity

object AuthenticationModes {
    const val PROPERTY = "snippets.auth.mode"
    const val DISABLED = "disabled"
    const val DEVELOPMENT = "dev"
    const val AUTH0 = "auth0"
    const val DEVELOPMENT_PROFILE = "dev"
    val SUPPORTED = setOf(DISABLED, DEVELOPMENT, AUTH0)
}
