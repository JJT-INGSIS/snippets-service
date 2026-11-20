package com.jjt.ingsis.snippets.telemetry

import com.newrelic.api.agent.NewRelic
import org.springframework.stereotype.Component

interface TransactionAttributes {
    fun add(
        name: String,
        value: String,
    )

    fun ignoreCurrent()
}

@Component
class NewRelicTransactionAttributes : TransactionAttributes {
    override fun add(
        name: String,
        value: String,
    ) = NewRelic.addCustomParameter(name, value)

    override fun ignoreCurrent() = NewRelic.ignoreTransaction()
}
