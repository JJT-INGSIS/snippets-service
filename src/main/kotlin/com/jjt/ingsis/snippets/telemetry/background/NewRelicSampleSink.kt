package com.jjt.ingsis.snippets.telemetry.background

import com.newrelic.api.agent.NewRelic

class NewRelicSampleSink : SampleSink {
    override fun record(samples: List<BackgroundSample>) {
        val insights = NewRelic.getAgent().insights
        samples.forEach { sample -> insights.recordCustomEvent(SAMPLE_EVENT, sample.attributes) }
    }
}
