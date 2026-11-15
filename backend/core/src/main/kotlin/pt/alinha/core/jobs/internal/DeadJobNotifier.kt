package pt.alinha.core.jobs.internal

import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import pt.alinha.core.audit.AuditTrail
import pt.alinha.core.jobs.JobError
import pt.alinha.core.jobs.JobFailed
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
    private val tx: TransactionTemplate,
    private val audit: AuditTrail,
    private val events: ApplicationEventPublisher,
    private val properties: JobsProperties,
) {
    @Scheduled(fixedDelayString = "\${alinha.jobs.relay.poll-interval-ms:1000}")
    fun poll() {
        if (properties.relay.enabled) notifyOnce()
    }

    /** @return dead jobs notified in this round. */
    fun notifyOnce(): Int = outbox.findDeadUnnotified(properties.relay.batchSize).count { job ->
        tenants.runAs(TenantId(job.tenantId)) { tx.execute { notify(job) } == true }
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
                occurredAt = Instant.now(),
                jobId = job.id,
                type = job.type,
                requestedBy = job.requestedBy,
                attempts = job.attempt,
                error = JobError(JobError.PUBLISH_FAILED, "Job could not be queued for the workers"),
            ),
        )
        return true
    }
}
