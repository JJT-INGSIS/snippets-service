package com.jjt.ingsis.snippets.storage.asset

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AssetEnvelopeTest {
    @Test
    fun `all code including empty strings has an explicit version and round trips exactly`() {
        listOf("", " ", "\r\ná🚀\n ", "\"\\\t\u0000", "{\"format\":\"snippet-content-v1\",\"code\":\"\"}")
            .forEach { code ->
                val bytes = checkNotNull(AssetEnvelope.encode(code, 1024))
                assertThat(bytes).isNotEmpty()
                assertThat(AssetEnvelope.decode(bytes, 1024)).isEqualTo(code)
                assertThat(bytes).isEqualTo(envelope(code))
            }
    }

    @Test
    fun `unknown corrupt ambiguous and raw objects are never guessed to be code`() {
        listOf(
            "",
            "println(1);",
            "null",
            "[]",
            "{}",
            """{"format":"snippet-content-v2","code":""}""",
            """{"format":1,"code":""}""",
            """{"format":"snippet-content-v1","code":null}""",
            """{"format":"snippet-content-v1","code":1}""",
            """{"format":"snippet-content-v1","code":"","extra":true}""",
            """{"format":"snippet-content-v1","code":"","code":"another"}""",
            """{"format":"snippet-content-v1","code":""} {}""",
            """{"format":"snippet-content-v1","code":"\ud800"}""",
        ).forEach { json -> assertThat(AssetEnvelope.decode(json.toByteArray(), 1024)).isNull() }
        assertThat(AssetEnvelope.decode(byteArrayOf(0xc3.toByte(), 0x28), 1024)).isNull()
    }

    @Test
    fun `code limits count UTF8 bytes and reject malformed JVM strings`() {
        assertThat(AssetEnvelope.encode("ññ", 4)).isNotNull()
        assertThat(AssetEnvelope.encode("ñññ", 4)).isNull()
        assertThat(AssetEnvelope.decode(envelope("ñññ"), 4)).isNull()
        assertThat(AssetEnvelope.encode("\uD800", 1024)).isNull()
        assertThat(AssetEnvelope.encode("\uDC00", 1024)).isNull()
    }

    @Test
    fun `wire limit accommodates worst case escaping without weakening the code limit`() {
        val limit = 16
        val bytes = checkNotNull(AssetEnvelope.encode("\u0000".repeat(limit), limit))
        assertThat(bytes.size).isEqualTo(AssetEnvelope.maxWireBytes(limit))
        assertThat(AssetEnvelope.decode(bytes, limit)).isEqualTo("\u0000".repeat(limit))
        assertThat(AssetEnvelope.decode(ByteArray(AssetEnvelope.maxWireBytes(limit) + 1), limit)).isNull()
        assertThat(AssetEnvelope.encode("\u0000".repeat(limit + 1), limit)).isNull()
    }
}
