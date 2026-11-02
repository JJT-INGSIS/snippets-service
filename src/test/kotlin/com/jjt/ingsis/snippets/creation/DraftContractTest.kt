package com.jjt.ingsis.snippets.creation

import com.jjt.ingsis.snippets.metadata.SnippetMetadata
import com.jjt.ingsis.snippets.metadata.SnippetState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

internal fun draft(): SnippetDraft =
    SnippetDraft(name = "Example", description = null, language = "printscript", version = "1.0", code = "println(1);")

internal fun reservedMetadata(state: SnippetState = SnippetState.PENDING): SnippetMetadata =
    SnippetMetadata(
        id = UUID.randomUUID(),
        name = "Example",
        description = null,
        language = "printscript",
        version = "1.0",
        state = state,
    )

class DraftContractTest {
    private val decoder = DraftJsonDecoder()

    @Test
    fun `required fields must be strings and description is nullable`() {
        val json = """{"name":"Example","language":"printscript","version":"1.0","code":"println(1);"}"""
        val accepted = assertInstanceOf(DraftInput.Accepted::class.java, decoder.decode(json))
        assertEquals("println(1);", accepted.draft.code)
        assertNull(accepted.draft.description)
        listOf("null", "1", "true", "[]", "{}").forEach { value ->
            assertEquals(
                DraftInput.Rejected(),
                decoder.decode(json.replace("\"code\":\"println(1);\"", "\"code\":$value")),
            )
        }
        assertEquals(DraftInput.Rejected(), decoder.decode("{}"))
        assertEquals(DraftInput.Rejected(), decoder.decode("[]"))
        assertEquals(DraftInput.Rejected(), decoder.decode("not json"))
        assertEquals(DraftInput.Rejected(), decoder.decode("$json {}"))
    }

    @Test
    fun `rejects fields that claim ownership identity or fingerprint`() {
        val json = """{"name":"Example","language":"printscript","version":"1.0","code":"println(1);"}"""
        listOf("ownerId", "actorId", "fingerprint").forEach { field ->
            assertEquals(DraftInput.Rejected(), decoder.decode(json.dropLast(1) + ",\"$field\":\"attacker\"}"))
        }
        assertEquals(DraftInput.Rejected(), decoder.decode(json.replace("Example", " ")))
        assertEquals(DraftInput.Rejected(), decoder.decode(json.replace("printscript", " ")))
        assertEquals(DraftInput.Rejected(), decoder.decode(json.replace("1.0", " ")))
    }

    @Test
    fun `blank code is a typed field rejection while admitted code retains every character`() {
        val empty = """{"name":"Example","language":"printscript","version":"1.0","code":""}"""
        assertEquals(DraftInput.Rejected(field = "code"), decoder.decode(empty))
        listOf("", " ", "\t", "\r\n", " \t\r\n", "\u00A0", "\u2003").forEach { code ->
            assertEquals("code", draft().copy(code = code).invalidField())
        }
        assertNull(draft().copy(code = "// comentario").invalidField())
        val original = "\uFEFF  println(1);\r\n\t"
        assertEquals(null, draft().copy(code = original).invalidField())
        assertEquals(original, draft().copy(code = original).code)
    }

    @Test
    fun `description preserves null empty and whitespace without normalizing`() {
        val json = """{"name":" Example ","language":"printscript","version":"1.0","code":"println(1);"}"""
        val accepted = assertInstanceOf(DraftInput.Accepted::class.java, decoder.decode(json)).draft
        assertEquals(" Example ", accepted.name)
        assertEquals(
            accepted,
            assertInstanceOf(
                DraftInput.Accepted::class.java,
                decoder.decode(json.dropLast(1) + ",\"description\":null}"),
            ).draft,
        )
        val empty = decoder.decode(json.dropLast(1) + ",\"description\":\"\"}")
        assertEquals("", assertInstanceOf(DraftInput.Accepted::class.java, empty).draft.description)
        assertEquals(DraftInput.Rejected(), decoder.decode(json.dropLast(1) + ",\"description\":4}"))
    }

    @Test
    fun `only canonical UUID keys are accepted`() {
        val key = UUID.randomUUID()
        assertEquals(key, canonicalUuid(key.toString()))
        listOf(null, "", "1-1-1-1-1", " $key", "invalid").forEach { assertNull(canonicalUuid(it)) }
    }

    @Test
    fun `pending metadata cannot be exposed as a successful creation response`() {
        val metadata = reservedMetadata()
        assertNull(CreationResponse.fromConfirmed(metadata))
        assertEquals(metadata.id, CreationResponse.fromConfirmed(metadata.copy(state = SnippetState.CONFIRMED))?.id)
    }

    @Test
    fun `documentation request is executable and independent of JSON field order`() {
        val document = Files.readString(Path.of("docs/creation.md"))
        val json = Regex("```json\\s*([\\s\\S]*?)```").find(document)?.groupValues?.get(1)
        val fromDocs = assertInstanceOf(DraftInput.Accepted::class.java, decoder.decode(checkNotNull(json))).draft
        assertEquals(draft(), fromDocs)
        val reordered =
            """{"code":"println(1);","version":"1.0","language":"printscript", "description":null,"name":"Example"}"""
        assertEquals(fromDocs, assertInstanceOf(DraftInput.Accepted::class.java, decoder.decode(reordered)).draft)
    }
}

class CreationFingerprintTest {
    private val fingerprint = CreationFingerprint()

    @Test
    fun `every relevant field changes the fingerprint including null versus empty`() {
        val original = draft()
        val expected = fingerprint.calculate(original)
        assertEquals(expected, fingerprint.calculate(original.copy()))
        assertEquals(74, expected.length)
        listOf(
            original.copy(name = "Other"),
            original.copy(description = ""),
            original.copy(description = "other"),
            original.copy(language = "other"),
            original.copy(version = "1.1"),
            original.copy(code = "println(2);"),
        ).forEach { assertNotEquals(expected, fingerprint.calculate(it)) }
    }

    @Test
    fun `uses unambiguous UTF8 JSON encoding`() {
        assertNotEquals(
            fingerprint.calculate(draft().copy(name = "ab", description = "c")),
            fingerprint.calculate(draft().copy(name = "a", description = "bc")),
        )
        val unicode = draft().copy(name = "á 🚀", description = "\"\\\n")
        assertEquals(fingerprint.calculate(unicode), fingerprint.calculate(unicode.copy()))
    }
}
