package com.jjt.ingsis.snippets.language.printscript.execution

import java.io.InputStream
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// HttpClient's header timeout does not bound consumption of an InputStream response body.
internal fun <T> timedExecutionBody(
    input: InputStream,
    timeout: Duration,
    read: (InputStream) -> T,
): T {
    val timer = Executors.newSingleThreadScheduledExecutor()
    val deadline = timer.schedule({ input.close() }, timeout.toMillis(), TimeUnit.MILLISECONDS)
    return try {
        input.use(read)
    } finally {
        deadline.cancel(false)
        timer.shutdownNow()
    }
}
