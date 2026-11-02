package com.jjt.ingsis.snippets.background

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_RECEIVED_ENVELOPE_BYTES = 16 * 1024

@Component
class JobConsumer(
    private val jobs: JobProgressStore,
    private val rejected: RejectedDeliveries,
    private val capacity: ExecutionCapacity,
    private val heartbeat: LeaseHeartbeat,
    handlers: List<BackgroundHandler>,
) {
    private val handlers = handlers.associateBy { it.eventType }
    private val json = JsonMapper.builder().findAndAddModules().build()
    private val running = AtomicBoolean(false)
    private val logger = LoggerFactory.getLogger(JobConsumer::class.java)

    init {
        require(this.handlers.size == handlers.size) { "Only one handler per event type is supported" }
    }

    fun cycle(inbox: StreamInbox) {
        if (!running.compareAndSet(false, true)) return
        try {
            inbox.initialize()
            (inbox.fresh() + inbox.recover()).distinctBy { it.id }.forEach { receive(it, inbox) }
            jobs.reconciliations().forEach { id -> jobs.find(id)?.let { execute(it) } }
        } finally {
            running.set(false)
        }
    }

    fun receive(
        delivery: StreamDelivery,
        inbox: StreamInbox,
    ) {
        val event = decode(delivery)
        if (event == null) {
            rejected.persist(delivery, "INVALID_ENVELOPE")
            inbox.acknowledge(delivery)
            return
        }
        val job = jobs.find(event.jobId)
        if (job == null || job.event != event) {
            rejected.persist(delivery, "EVENT_JOB_MISMATCH")
            inbox.acknowledge(delivery)
            return
        }
        if (job.state in terminalJobs || execute(job)) inbox.acknowledge(delivery)
    }

    private fun decode(delivery: StreamDelivery): DomainEvent? {
        val payload = delivery.payload ?: return null
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_RECEIVED_ENVELOPE_BYTES) return null
        return try {
            json.readValue(payload, DomainEvent::class.java)
        } catch (
            _: JacksonException,
        ) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // This is the plugin boundary: unexpected handler bugs must become durable retry/UNKNOWN, not drop deliveries.
    @Suppress("TooGenericExceptionCaught")
    private fun execute(job: StoredJob): Boolean {
        val handler = handlers[job.event.eventType]
        if (handler == null) {
            jobs.defer(job.event.jobId)
            return false
        }
        val reserved = jobs.claim(job.event.jobId) ?: return false
        val lease = checkNotNull(reserved.lease)
        return try {
            heartbeat.watch(lease).use {
                val work = JobWork(reserved, jobs, capacity)
                val permit = capacity.held(lease.jobId)
                val step =
                    try {
                        if (permit != null || reserved.state == JobState.UNKNOWN) {
                            handler.reconcile(work, permit)
                        } else {
                            handler.step(work)
                        }
                    } catch (lost: LostJobLease) {
                        throw lost
                    } catch (failure: RuntimeException) {
                        logger.warn(
                            "Job handler failed eventId={} jobId={} failure={}",
                            job.event.eventId,
                            lease.jobId,
                            failure.javaClass.simpleName,
                        )
                        JobStep.Retry("HANDLER_FAILURE")
                    }
                val ack = jobs.finish(lease, step)
                logger.info(
                    "Job turn eventId={} jobId={} correlationId={} outcome={} ackEligible={}",
                    job.event.eventId,
                    lease.jobId,
                    job.event.correlationId,
                    step.javaClass.simpleName,
                    ack,
                )
                ack
            }
        } catch (_: LostJobLease) {
            false
        }
    }

    private companion object {
        val terminalJobs = setOf(JobState.COMPLETED, JobState.PARTIAL, JobState.FAILED, JobState.SUPERSEDED)
    }
}
