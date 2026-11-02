package com.jjt.ingsis.snippets.reformatting

import com.jjt.ingsis.snippets.metadata.CreationRequest
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.metadata.SnippetDetails
import com.jjt.ingsis.snippets.metadata.SnippetMetadataStore
import com.jjt.ingsis.snippets.storage.ContentReference
import com.jjt.ingsis.snippets.storage.ReadResult
import com.jjt.ingsis.snippets.storage.SnippetContentStorage
import com.jjt.ingsis.snippets.storage.StoreResult
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

class SnippetSeeds(
    private val metadata: SnippetMetadataStore,
    private val links: SnippetContentLinks,
    private val storage: SnippetContentStorage,
    private val jdbc: JdbcTemplate,
) {
    fun confirmed(
        code: String,
        version: String,
        ownerId: String,
    ): UUID {
        val key = UUID.randomUUID()
        metadata.prepareCreation(
            CreationRequest(
                key = key,
                actorId = ownerId,
                fingerprint = UUID.randomUUID().toString(),
                details = details(version),
            ),
        )
        val id =
            checkNotNull(jdbc.queryForObject("SELECT id FROM snippets WHERE creation_key = ?", UUID::class.java, key))
        links.linkContent(id, store(code))
        metadata.confirmCreation(id)
        return id
    }

    fun edit(
        id: UUID,
        code: String,
    ) {
        links.replaceContent(id, details("1.1"), store(code))
    }

    fun reference(id: UUID): ContentReference? = links.findLinked(id)?.reference

    fun code(reference: ContentReference): String = (storage.read(reference) as ReadResult.Found).code

    private fun store(code: String): ContentReference = (storage.store(code) as StoreResult.Stored).reference

    private fun details(version: String): SnippetDetails =
        SnippetDetails(name = "Example", description = null, language = "printscript", version = version)
}
