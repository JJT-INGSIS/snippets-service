package com.jjt.ingsis.snippets.processing

import com.jjt.ingsis.snippets.background.RemotePermit
import com.jjt.ingsis.snippets.background.WorkItem
import com.jjt.ingsis.snippets.detail.ContentReading
import com.jjt.ingsis.snippets.detail.StableContentRead
import com.jjt.ingsis.snippets.execution.SnapshotFingerprint
import com.jjt.ingsis.snippets.language.ProcessorRevision
import com.jjt.ingsis.snippets.language.operations.ProcessingCommand
import com.jjt.ingsis.snippets.language.operations.ProcessingKind
import com.jjt.ingsis.snippets.language.operations.ProcessingOperation
import com.jjt.ingsis.snippets.metadata.SnippetContentLinks
import com.jjt.ingsis.snippets.storage.ContentReference
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

data class ProcessingSelection(
    val kind: ProcessingKind,
    val rules: Map<String, Any?>,
    val revision: String,
    val processor: ProcessorRevision,
)

data class ProcessingBasis(
    val snippetId: UUID,
    val owner: String,
    val reference: ContentReference,
    val language: String,
    val version: String,
    val rules: String,
    val processor: ProcessorRevision,
    val codeHash: String,
) {
    companion object {
        fun of(permit: RemotePermit): ProcessingBasis? {
            val remote = permit.remote ?: return null
            val context = remote.context
            val keys = listOf("snippetId", "owner", "content", "language", "version", "rules", "codeHash")
            if (keys.any { it !in context } || remote.processor == null) return null
            return ProcessingBasis(
                snippetId = UUID.fromString(context.getValue("snippetId")),
                owner = context.getValue("owner"),
                reference = ContentReference(context.getValue("content")),
                language = context.getValue("language"),
                version = context.getValue("version"),
                rules = context.getValue("rules"),
                processor = ProcessorRevision(remote.processor),
                codeHash = context.getValue("codeHash"),
            )
        }
    }
}

@Component
class ProcessingContent(
    private val links: SnippetContentLinks,
    private val content: ContentReading,
    private val fingerprints: SnapshotFingerprint,
) {
    fun read(item: WorkItem): StableContentRead =
        links.findLinked(UUID.fromString(item.key))?.let(content::readStable)
            ?: StableContentRead.Conflict

    fun prepare(
        read: StableContentRead.Read,
        owner: String,
        selection: ProcessingSelection,
    ): OperationPreparation.Ready {
        val metadata = read.snippet.metadata
        return OperationPreparation.Ready(
            command =
                ProcessingCommand(
                    kind = selection.kind,
                    version = metadata.version,
                    code = read.code,
                    rules = selection.rules,
                    processorRevision = selection.processor.value,
                    submittedAt = Instant.now(),
                ),
            context =
                mapOf(
                    "snippetId" to metadata.id.toString(),
                    "owner" to owner,
                    "content" to checkNotNull(read.snippet.reference).value,
                    "language" to metadata.language,
                    "rules" to selection.revision,
                    "codeHash" to fingerprints.code(metadata.language, metadata.version, read.code),
                ),
        )
    }

    fun current(basis: ProcessingBasis): Boolean =
        links.findLinked(basis.snippetId)?.let {
            it.reference == basis.reference && it.metadata.language == basis.language &&
                it.metadata.version == basis.version
        } == true

    fun matches(
        basis: ProcessingBasis,
        operation: ProcessingOperation,
    ): Boolean =
        operation.command?.let {
            fingerprints.code(basis.language, basis.version, it.code) == basis.codeHash
        } == true
}
