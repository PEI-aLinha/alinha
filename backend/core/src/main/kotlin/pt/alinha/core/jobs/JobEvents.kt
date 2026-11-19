package pt.alinha.core.jobs

import pt.alinha.pluginapi.DomainEvent
import pt.alinha.pluginapi.TenantId
import java.util.UUID
import java.time.Instant

/**
 * Why a job did not complete. [PUBLISH_FAILED] comes from the core; any other code comes from the worker, and its
 * [message] is untrusted text: show it, never act on it.
 */
data class JobError(val code: String, val message: String) {
    companion object {
        const val PUBLISH_FAILED = "publish_failed"
        val PUBLISH_FAILED_ERROR = JobError(PUBLISH_FAILED, "Job could not be queued for the workers")
    }
}

/**
 * The worker finished the job. The result is not in the event (it can be large): read it with
 * [JobResults.find] inside `runAs(tenantId)`. Filter by [requestedBy]/[type]; listener recipe in docs/jobs.md.
 */
data class JobCompleted(
    override val tenantId: TenantId,
    override val occurredAt: Instant,
    val jobId: UUID,
    val type: String,
    val requestedBy: String,
    val attempt: Int,
) : DomainEvent

/** The job will not run again. Filter by [requestedBy]/[type]; listener recipe in docs/jobs.md. */
data class JobFailed(
    override val tenantId: TenantId,
    override val occurredAt: Instant,
    val jobId: UUID,
    val type: String,
    val requestedBy: String,
    val attempt: Int,
    val error: JobError,
) : DomainEvent
