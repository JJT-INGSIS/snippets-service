package com.jjt.ingsis.snippets.storage.local

import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StoreResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.nio.file.Path

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:storage;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "storage.local.directory=build/tmp/storage-wiring-test",
    ],
)
class LocalStorageWiringTest(
    @Autowired private val storage: SnippetContentStorage,
) {
    @Test
    fun `the application stores snippet content in the configured directory`() {
        val result = storage.store("println(1);")
        check(result is StoreResult.Stored) { "The code was not stored: $result" }

        assertThat(storage).isInstanceOf(LocalSnippetContentStorage::class.java)
        assertThat(Path.of("build/tmp/storage-wiring-test").resolve(result.reference.value)).hasContent("println(1);")
        assertThat(storage.read(result.reference)).isEqualTo(ReadResult.Found("println(1);"))
    }
}
