package com.jjt.ingsis.snippets.storage.local

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ContentDirectoryTest(
    @param:TempDir private val workspace: Path,
) {
    @Test
    fun `creates the directory, with its parents, when it does not exist`() {
        val location = workspace.resolve("data").resolve("content")

        ContentDirectory.prepare(location.toString())

        assertThat(location).isDirectory()
    }

    @Test
    fun `refuses to start on a location that cannot be a directory`() {
        val file = Files.writeString(workspace.resolve("occupied"), "x")

        assertThatIllegalStateException()
            .isThrownBy { ContentDirectory.prepare(file.toString()) }
            .withMessageContaining(file.toString())
    }
}
