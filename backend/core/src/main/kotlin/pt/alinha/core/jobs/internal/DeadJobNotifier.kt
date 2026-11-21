package pt.alinha.core.jobs.internal

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import pt.alinha.core.audit.AuditTrail
import pt.alinha.core.jobs.JobError
import pt.alinha.core.jobs.JobFailed
import pt.alinha.core.tenant.CorrelationContext
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.TenantId
import java.time.Instant

/**
 * Emits JobFailed for jobs the relay marked dead. Outside the relay's cross-tenant batch: each job gets its own
 * runAs(tenant) + transaction (runAs refuses an open one), and the audit entry commits with the event.
 */
@Component
class DeadJobNotifier(
    private val outbox: JobOutboxRepository,
    private val tenants: TenantContext,
    private val correlation: CorrelationContext,
    private val tx: TransactionTemplate,
    private val audit: AuditTrail,
    private val events: ApplicationEventPublisher,
    private val properties: JobsProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${alinha.jobs.relay.poll-interval-ms:1000}")
    fun poll() {
        if (properties.relay.enabled) notifyOnce()
    }

    /** @return dead jobs notified in this round. A job that fails is retried next round without blocking the rest. */
    fun notifyOnce(): Int = outbox.findDeadUnnotified(properties.relay.batchSize).count { job ->
        try {
            tenants.runAs(TenantId(job.tenantId)) { tx.execute { notify(job) } == true }
        } catch (e: RuntimeException) {
            log.error("Could not emit JobFailed for dead job {} ({}), retrying next round", job.id, job.type, e)
            false
        }
    }

    private fun notify(job: OutboxJob): Boolean {
        if (!outbox.markNotified(job.id)) return false // another instance got there first
        audit.record(
            action = "job.dead",
            entityType = "job",
            entityId = job.id.toString(),
            details = mapOf("type" to job.type, "version" to job.version, "publishFailures" to job.publishFailures),
        )
        events.publishEvent(
            JobFailed(
                tenantId = TenantId(job.tenantId),
                correlationId = correlation.current(),
                occurredAt = Instant.now(),
                jobId = job.id,
                type = job.type,
                requestedBy = job.requestedBy,
                attempt = job.attempt,
                error = JobError.PUBLISH_FAILED_ERROR,
            ),
        )
        return true
    }
}
