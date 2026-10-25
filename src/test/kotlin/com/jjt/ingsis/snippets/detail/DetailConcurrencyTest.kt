package com.jjt.ingsis.snippets.detail

import com.jjt.ingsis.snippets.creation.SnippetDraft
import com.jjt.ingsis.snippets.metadata.MetadataTestConfiguration
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StoreResult
import com.jjt.ingsis.snippets.update.SnippetReplacement
import com.jjt.ingsis.snippets.update.UpdateCandidate
import com.jjt.ingsis.snippets.update.UpdateOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val UPDATES = 150

private fun codeOf(name: String): String = "println(\"$name\");"

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class)
class DetailConcurrencyTest
    @Autowired
    constructor(
        private val storage: SnippetContentStorage,
        private val links: SnippetContentLinks,
        private val replacement: SnippetReplacement,
        private val reading: ContentReading,
        private val jdbc: JdbcTemplate,
    ) {
        @Test
        fun `reads during updates are coherent or a retryable failure`() {
            val id = storedSnippet("v0")

            val results =
                Executors.newFixedThreadPool(2).use { executor ->
                    val writer = executor.submit(Callable { (1..UPDATES).map { version -> update(id, "v$version") } })
                    val reader =
                        executor.submit(
                            Callable { generateSequence { if (writer.isDone) null else readOnce(id) }.toList() },
                        )
                    assertThat(writer.get(2, TimeUnit.MINUTES)).containsOnly(true)
                    reader.get(2, TimeUnit.MINUTES)
                }

            val reads = results.filterIsInstance<ContentRead.Read>()
            assertThat(reads).isNotEmpty()
            assertThat(reads).allSatisfy { read -> assertThat(read.code).isEqualTo(codeOf(read.snippet.metadata.name)) }
            assertThat(results).allMatch { it is ContentRead.Read || it == ContentRead.Unavailable }
            val last = checkNotNull(links.findLinked(id))
            assertThat(reading.read(last)).isEqualTo(ContentRead.Read(last, codeOf("v$UPDATES")))
        }

        private fun readOnce(id: UUID): ContentRead = reading.read(checkNotNull(links.findLinked(id)))

        private fun update(
            id: UUID,
            name: String,
        ): Boolean {
            val draft =
                SnippetDraft(
                    name = name,
                    description = null,
                    language = "printscript",
                    version = "1.0",
                    code = codeOf(name),
                )
            return replacement.apply(UpdateCandidate(id, draft)) is UpdateOutcome.Updated
        }

        private fun storedSnippet(name: String): UUID {
            val id = UUID.randomUUID()
            val reference = (storage.store(codeOf(name)) as StoreResult.Stored).reference
            jdbc.update(
                "INSERT INTO snippets (id, name, description, language, version, state, creation_key, " +
                    "creation_actor_id, creation_fingerprint, content_reference) " +
                    "VALUES (?, ?, NULL, 'printscript', '1.0', 'CONFIRMED', ?, 'dev-juan', 'f', ?)",
                id,
                name,
                UUID.randomUUID(),
                reference.value,
            )
            return id
        }

        companion object {
            @JvmStatic
            @DynamicPropertySource
            fun storage(registry: DynamicPropertyRegistry) {
                registry.add("storage.local.directory") { Files.createTempDirectory("detail-race").toString() }
            }
        }
    }
