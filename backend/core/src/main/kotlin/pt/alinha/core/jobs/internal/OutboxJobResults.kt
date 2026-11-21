package pt.alinha.core.jobs.internal

import org.springframework.stereotype.Component
import pt.alinha.core.jobs.JobError
import pt.alinha.core.jobs.JobResult
import pt.alinha.core.jobs.JobResults
import pt.alinha.core.jobs.JobStatus
import pt.alinha.core.tenant.TenantContext
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.util.UUID

@Component
class OutboxJobResults(
    private val outbox: JobOutboxRepository,
    private val tenants: TenantContext,
    private val json: JsonMapper,
) : JobResults {

    override fun find(jobId: UUID): JobResult? {
        val state = outbox.findState(jobId, tenants.current().value) ?: return null
        val status = JobStatus.valueOf(state.status.uppercase())
        val document: Map<String, Any?> = state.document?.let { json.readValue(it) } ?: emptyMap()
        return JobResult(
            jobId = state.id,
            type = state.type,
            status = status,
            attempt = state.attempt,
            result = if (status == JobStatus.COMPLETED) document.objectField(RESULT) ?: emptyMap() else null,
            error = when (status) {
                JobStatus.FAILED -> document.objectField(ERROR)?.let { JobError(it.text("code"), it.text("message")) }
                JobStatus.DEAD -> JobError.PUBLISH_FAILED_ERROR
                else -> null
            },
            finishedAt = state.finishedAt,
        )
    }

    private companion object {
        const val RESULT = "result"
        const val ERROR = "error"

        @Suppress("UNCHECKED_CAST")
        fun Map<String, Any?>.objectField(name: String) = this[name] as? Map<String, Any?>

        fun Map<String, Any?>.text(name: String) = this[name]?.toString().orEmpty()
    }
}
