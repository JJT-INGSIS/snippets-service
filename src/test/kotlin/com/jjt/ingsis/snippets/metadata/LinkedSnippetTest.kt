package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.storage.ContentReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MetadataTestConfiguration::class)
class LinkedSnippetTest
    @Autowired
    constructor(
        private val store: SnippetMetadataStore,
        private val links: SnippetContentLinks,
    ) {
        @Test
        fun `details and content reference come from the same row`() {
            val reference = ContentReference(UUID.randomUUID().toString())
            val pending = pendingSnippet()
            links.linkContent(pending.id, reference)
            val confirmed = checkNotNull(store.confirmCreation(pending.id))

            assertThat(links.findLinked(confirmed.id)).isEqualTo(LinkedSnippet(confirmed, reference))
        }

        @Test
        fun `a replacement is seen with its own details and reference`() {
            val pending = pendingSnippet()
            links.linkContent(pending.id, ContentReference(UUID.randomUUID().toString()))
            store.confirmCreation(pending.id)
            val details =
                SnippetDetails(name = "Renamed", description = "Changed", language = "printscript", version = "1.1")
            val replacement = ContentReference(UUID.randomUUID().toString())
            val replaced = links.replaceContent(pending.id, details, replacement) as ContentReplacementResult.Replaced

            assertThat(links.findLinked(pending.id)).isEqualTo(LinkedSnippet(replaced.metadata, replacement))
        }

        @Test
        fun `pending snippets are returned with their state and missing ones are null`() {
            val pending = pendingSnippet()

            assertThat(links.findLinked(pending.id)).isEqualTo(LinkedSnippet(pending, null))
            assertThat(links.findLinked(UUID.randomUUID())).isNull()
        }

        @Test
        fun `database failure is not a missing snippet`() {
            val dataSource = mock(DataSource::class.java) { throw SQLException("Database unavailable") }
            val unavailable =
                JdbcSnippetContentLinks(
                    SnippetContentRows(JdbcTemplate(dataSource)),
                    MetadataTransaction(JdbcTransactionManager(dataSource)),
                )

            assertThatThrownBy { unavailable.findLinked(UUID.randomUUID()) }
                .isInstanceOf(MetadataPersistenceException::class.java)
        }

        private fun pendingSnippet(): SnippetMetadata =
            (store.prepareCreation(creationRequest()) as CreationResult.Created).metadata
    }
