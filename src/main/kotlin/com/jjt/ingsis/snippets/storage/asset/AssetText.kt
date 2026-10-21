package com.jjt.ingsis.snippets.storage.asset

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException

internal object AssetText {
    fun encode(
        code: String,
        limit: Int,
    ): ByteArray? {
        if (code.length > limit) return null
        return try {
            val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(code))
            if (encoded.remaining() > limit) null else ByteArray(encoded.remaining()).also { encoded.get(it) }
        } catch (_: CharacterCodingException) {
            null
        }
    }

    fun decode(bytes: ByteArray): String? =
        try {
            Charsets.UTF_8
                .newDecoder()
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
}
