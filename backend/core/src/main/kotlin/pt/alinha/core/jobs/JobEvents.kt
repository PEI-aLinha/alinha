package pt.alinha.core.jobs

import pt.alinha.pluginapi.DomainEvent
import pt.alinha.pluginapi.TenantId
import java.util.UUID
import java.time.Instant

data class JobError(val code: String, val message: String) {
    companion object {
        const val PUBLISH_FAILED = "publish_failed"
    }
}

/** The job will not run. Listen with @ApplicationModuleListener and filter by [requestedBy]/[type]. */
data class JobFailed(
    override val tenantId: TenantId,
    override val occurredAt: Instant,
    val jobId: UUID,
    val type: String,
    val requestedBy: String,
    val attempts: Int,
    val error: JobError,
) : DomainEvent
