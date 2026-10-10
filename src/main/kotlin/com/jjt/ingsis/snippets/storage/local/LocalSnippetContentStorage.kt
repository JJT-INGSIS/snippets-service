package com.jjt.ingsis.snippets.storage.local

import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.DeleteResult
import com.jjt.ingsis.snippets.storage.InvalidContentReference
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StorageUnavailable
import com.jjt.ingsis.snippets.storage.StoreResult
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.UUID

class LocalSnippetContentStorage(
    private val directory: ContentDirectory,
) : SnippetContentStorage {
    private val logger = LoggerFactory.getLogger(LocalSnippetContentStorage::class.java)

    override fun store(code: String): StoreResult {
        val id = UUID.randomUUID()

        return try {
            AtomicTextFile.write(directory.fileFor(id), code)
            StoreResult.Stored(ContentReference(id.toString()))
        } catch (failure: IOException) {
            unavailable(failure)
        }
    }

    override fun read(reference: ContentReference): ReadResult {
        val file = directory.fileOf(reference) ?: return InvalidContentReference

        return try {
            ReadResult.Found(AtomicTextFile.read(file))
        } catch (missing: NoSuchFileException) {
            if (directory.isAvailable()) ReadResult.Missing else unavailable(missing)
        } catch (failure: IOException) {
            unavailable(failure)
        }
    }

    override fun delete(reference: ContentReference): DeleteResult {
        val file = directory.fileOf(reference) ?: return InvalidContentReference

        return try {
            Files.deleteIfExists(file)
            DeleteResult.Deleted
        } catch (failure: IOException) {
            unavailable(failure)
        }
    }

    private fun unavailable(failure: IOException): StorageUnavailable {
        logger.warn("The local snippet content storage failed", failure)

        return StorageUnavailable
    }
}
