package com.jjt.ingsis.snippets.metadata

import com.jjt.ingsis.snippets.storage.ContentReference
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class ContentRetention(
    private val jdbc: JdbcTemplate,
) {
    // The caller holds its transaction through the pointer/snapshot write. Cleanup fences new references.
    fun retain(reference: ContentReference) {
        jdbc.update("INSERT INTO retained_content (reference) VALUES (?) ON CONFLICT DO NOTHING", reference.value)
        val state =
            jdbc.queryForObject(
                "SELECT state FROM retained_content WHERE reference = ? FOR UPDATE",
                String::class.java,
                reference.value,
            )
        if (state != "AVAILABLE") {
            throw MetadataPersistenceException(IllegalStateException("Content reference is fenced for deletion"))
        }
    }
}
