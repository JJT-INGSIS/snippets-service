package com.jjt.ingsis.snippets.http

import java.io.File

internal data class ContractExample(
    val title: String,
    val status: Int,
    val request: String,
    val response: String,
)

internal data class DocumentedProblem(
    val status: Int,
    val type: String,
)

internal object CreationContract {
    private const val EXAMPLE_HEADING_PREFIX = "#### "

    private val sectionStart = Regex("^(?=#{1,6} )", RegexOption.MULTILINE)
    private val exampleHeading = Regex("^#### `(\\d{3})` (.+)")
    private val jsonBlock = Regex("```json\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
    private val problemRow = Regex("^\\| `(\\d{3})` \\| `(urn:snippets:problem:[a-z-]+)` \\|", RegexOption.MULTILINE)

    private val text: String = File("docs/creation.md").readText().replace("\r\n", "\n")

    val examples: List<ContractExample> =
        text
            .split(sectionStart)
            .filter { section -> section.startsWith(EXAMPLE_HEADING_PREFIX) }
            .map { section -> exampleIn(section) }

    val problems: List<DocumentedProblem> =
        problemRow
            .findAll(text)
            .map { row -> DocumentedProblem(status = row.groupValues[1].toInt(), type = row.groupValues[2]) }
            .toList()

    fun example(title: String): ContractExample =
        checkNotNull(examples.find { example -> example.title == title }) {
            "docs/creation.md has no example titled '$title'"
        }

    private fun exampleIn(section: String): ContractExample {
        val heading =
            checkNotNull(exampleHeading.find(section)) {
                "An example heading must look like: #### `201` Title. Found: ${section.lineSequence().first()}"
            }
        val blocks = jsonBlock.findAll(section).map { block -> block.groupValues[1] }.toList()

        check(blocks.size == 2) {
            "The example '${heading.groupValues[2]}' must have a request block and a response block"
        }

        return ContractExample(
            title = heading.groupValues[2],
            status = heading.groupValues[1].toInt(),
            request = blocks[0],
            response = blocks[1],
        )
    }
}
