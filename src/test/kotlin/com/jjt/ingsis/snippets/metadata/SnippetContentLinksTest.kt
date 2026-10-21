package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.storage.ContentReference
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class)
class SnippetContentLinksTest(
    @Autowired private val store: SnippetMetadataStore,
    @Autowired private val links: SnippetContentLinks,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val postgres: PostgreSQLContainer,
) {
    private val renamed =
        SnippetDetails(name = "Renamed", description = "Changed", language = "printscript", version = "1.1")

    @Test
    fun `a snippet has no content until one is linked`() {
        val pending = pendingSnippet()
        val reference = newReference()

        assertThat(links.findContent(pending.id)).isNull()
        assertThat(links.linkContent(pending.id, reference)).isEqualTo(ContentLinkResult.Linked(replaced = null))
        assertThat(links.findContent(pending.id)).isEqualTo(reference)
    }

    @Test
    fun `linking again while pending tells which content was replaced`() {
        val pending = pendingSnippet()
        val first = newReference()
        val second = newReference()
        links.linkContent(pending.id, first)

        assertThat(links.linkContent(pending.id, second)).isEqualTo(ContentLinkResult.Linked(replaced = first))
        assertThat(links.findContent(pending.id)).isEqualTo(second)
    }

    @Test
    fun `the content of a confirmed snippet cannot be changed by linking`() {
        val confirmed = confirmedSnippet(newReference())
        val current = links.findContent(confirmed.id)

        assertThat(links.linkContent(confirmed.id, newReference())).isEqualTo(ContentLinkResult.AlreadyConfirmed)
        assertThat(links.findContent(confirmed.id)).isEqualTo(current)
    }

    @Test
    fun `replaces the details and the content of a confirmed snippet together`() {
        val previous = newReference()
        val replacement = newReference()
        val confirmed = confirmedSnippet(previous)
        val expected =
            confirmed.copy(
                name = renamed.name,
                description = renamed.description,
                language = renamed.language,
                version = renamed.version,
            )

        assertThat(links.replaceContent(confirmed.id, renamed, replacement))
            .isEqualTo(ContentReplacementResult.Replaced(metadata = expected, replaced = previous))
        assertThat(store.findConfirmed(confirmed.id)).isEqualTo(expected)
        assertThat(links.findContent(confirmed.id)).isEqualTo(replacement)
    }

    @Test
    fun `a pending snippet keeps its details and its content when a replacement is attempted`() {
        val pending = pendingSnippet()
        val linked = newReference()
        links.linkContent(pending.id, linked)

        assertThat(links.replaceContent(pending.id, renamed, newReference()))
            .isEqualTo(ContentReplacementResult.StillPending)
        assertThat(store.findMetadata(pending.id)).isEqualTo(pending)
        assertThat(links.findContent(pending.id)).isEqualTo(linked)
    }

    @Test
    fun `a snippet that does not exist is reported and nothing is created`() {
        val id = UUID.randomUUID()

        assertThat(links.findContent(id)).isNull()
        assertThat(links.linkContent(id, newReference())).isEqualTo(SnippetMissing)
        assertThat(links.replaceContent(id, renamed, newReference())).isEqualTo(SnippetMissing)
        assertThat(store.findMetadata(id)).isNull()
    }

    @Test
    fun `concurrent replacements leave the details and the content of one same request`() {
        val initial = newReference()
        val confirmed = confirmedSnippet(initial)
        val first = Replacement(renamed.copy(name = "First"), newReference())
        val second = Replacement(renamed.copy(name = "Second"), newReference())
        val workers = Executors.newFixedThreadPool(2)

        try {
            val results =
                workers.invokeAll(
                    listOf(
                        Callable { links.replaceContent(confirmed.id, first.details, first.reference) },
                        Callable { links.replaceContent(confirmed.id, second.details, second.reference) },
                    ),
                )
            val replaced = results.map { result -> (result.get() as ContentReplacementResult.Replaced).replaced }
            val winner = if (links.findContent(confirmed.id) == first.reference) first else second
            val loser = if (winner == first) second else first

            assertThat(store.findConfirmed(confirmed.id)?.name).isEqualTo(winner.details.name)
            assertThat(replaced).containsExactlyInAnyOrder(initial, loser.reference)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `the link is added by a new migration that keeps the snippets that already existed`() {
        val schema = "upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        val id = UUID.randomUUID()

        migrate(schema, target = "1")
        jdbc.update(
            "INSERT INTO $schema.snippets " +
                "(id, name, language, version, state, creation_key, creation_actor_id, creation_fingerprint) " +
                "VALUES (?, 'Existing', 'printscript', '1.0', 'CONFIRMED', ?, 'dev-juan', 'fingerprint')",
            id,
            UUID.randomUUID(),
        )
        migrate(schema, target = "latest")

        val row = jdbc.queryForMap("SELECT name, state, content_reference FROM $schema.snippets WHERE id = ?", id)

        assertThat(row["name"]).isEqualTo("Existing")
        assertThat(row["state"]).isEqualTo("CONFIRMED")
        assertThat(row["content_reference"]).isNull()
    }

    private fun migrate(
        schema: String,
        target: String,
    ) {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas(schema)
            .target(target)
            .load()
            .migrate()
    }

    private fun pendingSnippet(): SnippetMetadata {
        val result = store.prepareCreation(creationRequest())
        check(result is CreationResult.Created) { "The snippet was not created: $result" }

        return result.metadata
    }

    private fun confirmedSnippet(reference: ContentReference): SnippetMetadata {
        val pending = pendingSnippet()
        links.linkContent(pending.id, reference)

        return checkNotNull(store.confirmCreation(pending.id))
    }

    private fun newReference(): ContentReference = ContentReference(UUID.randomUUID().toString())

    private data class Replacement(
        val details: SnippetDetails,
        val reference: ContentReference,
    )
}
