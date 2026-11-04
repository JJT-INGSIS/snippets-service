package com.jjt.ingsis.snippets.http

import com.jjt.ingsis.snippets.formattingrules.FormattedDownload
import com.jjt.ingsis.snippets.formattingrules.VariantSource
import java.util.UUID

data class FormattedSnippetResponse(
    val snippetId: UUID,
    val name: String,
    val language: String,
    val version: String,
    val extension: String,
    val code: String,
    val source: String,
    val rulesRevision: String,
    val processorRevision: String,
) {
    companion object {
        fun from(download: FormattedDownload.Ready): FormattedSnippetResponse =
            FormattedSnippetResponse(
                snippetId = download.snippet.id,
                name = download.snippet.name,
                language = download.snippet.language,
                version = download.snippet.version,
                extension = download.extension,
                code = download.code,
                source = sourceName(download.source),
                rulesRevision = download.basis.rules,
                processorRevision = download.basis.processor.value,
            )

        private fun sourceName(source: VariantSource): String =
            when (source) {
                VariantSource.STORED -> "stored"
                VariantSource.ON_DEMAND -> "on-demand"
            }
    }
}
