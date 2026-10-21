package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.storage.ContentReference
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

class ContentLinkFailureTest {
    private val unavailable = mock(DataSource::class.java) { throw SQLException("Database unavailable") }

    private val links =
        JdbcSnippetContentLinks(
            rows = SnippetContentRows(JdbcTemplate(unavailable)),
            transaction = MetadataTransaction(JdbcTransactionManager(unavailable)),
        )

    private val id = UUID.randomUUID()

    private val reference = ContentReference(UUID.randomUUID().toString())

    private val details =
        SnippetDetails(name = "Example", description = null, language = "printscript", version = "1.0")

    @Test
    fun `an unavailable database is neither a missing snippet nor a successful link`() {
        assertThatThrownBy { links.findContent(id) }.isInstanceOf(MetadataPersistenceException::class.java)
        assertThatThrownBy { links.linkContent(id, reference) }.isInstanceOf(MetadataPersistenceException::class.java)
        assertThatThrownBy { links.replaceContent(id, details, reference) }
            .isInstanceOf(MetadataPersistenceException::class.java)
    }
}
