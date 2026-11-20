package com.jjt.ingsis.snippets.language

const val PROCESSOR_REVISION_HEADER = "Processor-Revision"

private val revisionFormat = Regex("^sha256-v1:[0-9a-f]{64}$")

data class ProcessorRevision(
    val value: String,
) {
    init {
        require(revisionFormat.matches(value)) { "A processor revision is sha256-v1 followed by 64 hex digits" }
    }
}

enum class ProcessorCheck { ACCREDITED, MISMATCH, UNVERIFIABLE }

sealed interface ProcessorIdentity {
    data class Verified(
        val revision: ProcessorRevision,
    ) : ProcessorIdentity

    data object Missing : ProcessorIdentity

    data class Malformed(
        val value: String,
    ) : ProcessorIdentity

    fun verifiedOrNull(): ProcessorRevision? = if (this is Verified) revision else null

    fun checkAgainst(expected: ProcessorRevision): ProcessorCheck =
        when (this) {
            is Verified -> if (revision == expected) ProcessorCheck.ACCREDITED else ProcessorCheck.MISMATCH
            Missing, is Malformed -> ProcessorCheck.UNVERIFIABLE
        }

    companion object {
        fun of(header: String?): ProcessorIdentity =
            when {
                header == null -> Missing
                revisionFormat.matches(header) -> Verified(ProcessorRevision(header))
                else -> Malformed(header)
            }
    }
}

data class Attributed<out T>(
    val result: T,
    val processor: ProcessorIdentity,
)
