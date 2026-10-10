package com.jjt.ingsis.snippets.storage

@JvmInline
value class ContentReference(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A content reference must not be blank" }
    }
}
