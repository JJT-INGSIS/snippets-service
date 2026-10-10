package com.jjt.ingsis.snippets.storage.local

import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.DeleteResult
import com.jjt.ingsis.snippets.storage.InvalidContentReference
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.StorageUnavailable
import com.jjt.ingsis.snippets.storage.StoreResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class LocalSnippetContentStorageTest(
    @param:TempDir private val workspace: Path,
) {
    private val exactTexts =
        listOf(
            "",
            "println(1);",
            "let a: number = 1;\r\nprintln(a);\r\n",
            "  println(1);  \n\n\n",
            "\tprintln(\"ñandú 日本語 😀\");",
            "\uFEFFprintln(1);",
            "println(1);\u0000",
            "println(1);\n".repeat(20_000),
        )

    @Test
    fun `gives back exactly the code that was stored`() {
        val storage = storageIn("content")

        for (text in exactTexts) {
            assertThat(storage.read(stored(storage, text))).isEqualTo(ReadResult.Found(text))
        }
    }

    @Test
    fun `never overwrites content that is already stored`() {
        val storage = storageIn("content")
        val first = stored(storage, "println(1);")
        val second = stored(storage, "println(2);")
        val repeated = stored(storage, "println(1);")

        assertThat(setOf(first, second, repeated)).hasSize(3)
        assertThat(storage.read(first)).isEqualTo(ReadResult.Found("println(1);"))
        assertThat(storage.read(second)).isEqualTo(ReadResult.Found("println(2);"))
        assertThat(storage.read(repeated)).isEqualTo(ReadResult.Found("println(1);"))
    }

    @Test
    fun `keeps the content when the application starts again on the same directory`() {
        val reference = stored(storageIn("content"), "println(1);")

        assertThat(storageIn("content").read(reference)).isEqualTo(ReadResult.Found("println(1);"))
    }

    @Test
    fun `reports content that does not exist`() {
        val storage = storageIn("content")

        assertThat(storage.read(ContentReference(UUID.randomUUID().toString()))).isEqualTo(ReadResult.Missing)
    }

    @Test
    fun `deletes content and accepts the same deletion again`() {
        val storage = storageIn("content")
        val kept = stored(storage, "println(1);")
        val removed = stored(storage, "println(2);")

        assertThat(storage.delete(removed)).isEqualTo(DeleteResult.Deleted)
        assertThat(storage.delete(removed)).isEqualTo(DeleteResult.Deleted)
        assertThat(storage.read(removed)).isEqualTo(ReadResult.Missing)
        assertThat(storage.read(kept)).isEqualTo(ReadResult.Found("println(1);"))
    }

    @Test
    fun `refuses references that could reach outside its directory`() {
        val storage = storageIn("content")
        val outside = Files.writeString(workspace.resolve("secret"), "outside")
        val inside = stored(storage, "println(1);")
        val references =
            listOf(
                "../secret",
                "..\\secret",
                outside.toString(),
                "nested/${inside.value}",
                inside.value.uppercase(),
                " ${inside.value}",
                "${inside.value}.tmp",
                "not-a-reference",
            )

        for (reference in references) {
            assertThat(storage.read(ContentReference(reference))).isEqualTo(InvalidContentReference)
            assertThat(storage.delete(ContentReference(reference))).isEqualTo(InvalidContentReference)
        }
        assertThat(outside).hasContent("outside")
        assertThat(storage.read(inside)).isEqualTo(ReadResult.Found("println(1);"))
    }

    @Test
    fun `an interrupted write is never visible as content`() {
        val storage = storageIn("content")
        val reference = stored(storage, "println(1);")
        val interrupted = Files.writeString(workspace.resolve("content").resolve("incoming-123.tmp"), "printl")

        assertThat(storage.read(ContentReference(interrupted.fileName.toString()))).isEqualTo(InvalidContentReference)
        assertThat(storage.read(reference)).isEqualTo(ReadResult.Found("println(1);"))
    }

    @Test
    fun `stores nothing when the code cannot be preserved exactly`() {
        val storage = storageIn("content")
        val unpairedSurrogate = "println(\"\uD800\");"

        assertThat(storage.store(unpairedSurrogate)).isEqualTo(StorageUnavailable)
        assertThat(workspace.resolve("content")).isEmptyDirectory()
    }

    @Test
    fun `a directory that disappeared is a failure and not missing content`() {
        val storage = storageIn("content")
        val reference = stored(storage, "println(1);")

        Files.delete(workspace.resolve("content").resolve(reference.value))
        Files.delete(workspace.resolve("content"))

        assertThat(storage.store("println(2);")).isEqualTo(StorageUnavailable)
        assertThat(storage.read(reference)).isEqualTo(StorageUnavailable)
    }

    @Test
    fun `content that cannot be read or deleted is a failure`() {
        val storage = storageIn("content")
        val reference = ContentReference(UUID.randomUUID().toString())
        val unreadable = Files.createDirectory(workspace.resolve("content").resolve(reference.value))
        Files.writeString(unreadable.resolve("blocking-file"), "x")

        assertThat(storage.read(reference)).isEqualTo(StorageUnavailable)
        assertThat(storage.delete(reference)).isEqualTo(StorageUnavailable)
    }

    @Test
    fun `stores concurrent writes separately and completely`() {
        val storage = storageIn("content")
        val texts = (1..64).map { number -> "println($number);\n".repeat(2_000) }
        val workers = Executors.newFixedThreadPool(8)

        try {
            val references = workers.invokeAll(texts.map { text -> Callable { stored(storage, text) } })

            assertThat(references.map { reference -> reference.get() }.toSet()).hasSize(texts.size)
            for ((index, reference) in references.withIndex()) {
                assertThat(storage.read(reference.get())).isEqualTo(ReadResult.Found(texts[index]))
            }
        } finally {
            workers.shutdownNow()
        }
    }

    private fun storageIn(name: String): LocalSnippetContentStorage =
        LocalSnippetContentStorage(ContentDirectory.prepare(workspace.resolve(name).toString()))

    private fun stored(
        storage: LocalSnippetContentStorage,
        code: String,
    ): ContentReference {
        val result = storage.store(code)
        check(result is StoreResult.Stored) { "The code was not stored: $result" }

        return result.reference
    }
}
