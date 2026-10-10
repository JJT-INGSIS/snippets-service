package com.jjt.ingsis.snippets.storage.local

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

object AtomicTextFile {
    fun write(
        target: Path,
        text: String,
    ) {
        val incoming = Files.createTempFile(target.parent, "incoming-", ".tmp")

        try {
            Files.writeString(incoming, text, Charsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.DSYNC)
            Files.move(incoming, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(incoming)
        }
    }

    fun read(source: Path): String = Files.readString(source, Charsets.UTF_8)
}
