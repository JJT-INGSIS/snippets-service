package com.jjt.ingsis.snippets.storage.local

import com.jjt.ingsis.snippets.storage.ContentReference
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class ContentDirectory private constructor(
    private val root: Path,
) {
    fun isAvailable(): Boolean = Files.isDirectory(root)

    fun fileFor(id: UUID): Path = root.resolve(id.toString())

    fun fileOf(reference: ContentReference): Path? {
        val id = canonicalIdOf(reference) ?: return null

        return fileFor(id)
    }

    private fun canonicalIdOf(reference: ContentReference): UUID? {
        val id =
            try {
                UUID.fromString(reference.value)
            } catch (_: IllegalArgumentException) {
                return null
            }

        return if (id.toString() == reference.value) id else null
    }

    companion object {
        fun prepare(location: String): ContentDirectory {
            val root = Path.of(location).toAbsolutePath().normalize()

            try {
                Files.createDirectories(root)
            } catch (failure: IOException) {
                throw IllegalStateException("The snippet content directory $root cannot be created", failure)
            }
            check(Files.isWritable(root)) { "The snippet content directory $root is not writable" }

            return ContentDirectory(root)
        }
    }
}
