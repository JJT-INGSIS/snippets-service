package com.jjt.ingsis.snippets.telemetry.background

const val SAMPLE_EVENT = "SnippetBackgroundSample"

data class BackgroundSample(
    val aspect: String,
    val attributes: Map<String, Any>,
)

fun interface SampleSink {
    fun record(samples: List<BackgroundSample>)
}
