package com.jjt.ingsis.snippets.background

import com.jjt.ingsis.snippets.metadata.MetadataPersistenceException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Component
class LeaseHeartbeat(
    private val jobs: JobProgressStore,
    private val settings: WorkerSettings,
) : AutoCloseable {
    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "job-lease-renewal").apply { isDaemon = true }
        }
    private val logger = LoggerFactory.getLogger(LeaseHeartbeat::class.java)

    fun watch(lease: JobLease): AutoCloseable {
        val renewal =
            scheduler.scheduleWithFixedDelay({
                try {
                    if (!jobs.renew(lease)) logger.warn("Job lease lost jobId={}", lease.jobId)
                } catch (failure: MetadataPersistenceException) {
                    // A failed heartbeat never releases the remote permit; all later writes still check the DB token.
                    logger.warn(
                        "Job lease renewal unavailable jobId={} failure={}",
                        lease.jobId,
                        failure.javaClass.simpleName,
                    )
                }
            }, settings.lease.toMillis() / 3, settings.lease.toMillis() / 3, TimeUnit.MILLISECONDS)
        return AutoCloseable { renewal.cancel(false) }
    }

    override fun close() {
        scheduler.shutdownNow()
    }
}
