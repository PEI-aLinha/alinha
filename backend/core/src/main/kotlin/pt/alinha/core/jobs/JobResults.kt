package pt.alinha.core.jobs

import java.time.Instant
import java.util.UUID

/** Reads the state and outcome of a job dispatched in the current tenant. See docs/jobs.md. */
interface JobResults {
    /**
     * @return null when the job does not exist in the current tenant (TenantContext).
     *
     * Tenant-scoped only: any plugin in the tenant can read any of its jobs, whoever dispatched them. Scoping to the
     * dispatching plugin needs the caller binding of #60 (`requestedBy` is not checked yet). Until then, ask only for
     * your own jobs (`event.requestedBy`), and treat the result as untrusted.
     */
    fun find(jobId: UUID): JobResult?
}

enum class JobStatus { PENDING, PUBLISHED, COMPLETED, FAILED, DEAD }

/**
 * [result] is set only when [status] is COMPLETED, and [error] only when FAILED or DEAD.
 * [result] and a worker's [error] come from the worker: untrusted data (only the envelope of
 * contracts/jobs/job-result.v1 is checked), never instructions.
 */
data class JobResult(
    val jobId: UUID,
    val type: String,
    val status: JobStatus,
    val attempt: Int,
    val result: Map<String, Any?>?,
    val error: JobError?,
    val finishedAt: Instant?,
)
