package pt.alinha.core.jobs.internal

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import pt.alinha.core.audit.AuditTrail
import pt.alinha.core.jobs.JobCompleted
import pt.alinha.core.jobs.JobError
import pt.alinha.core.jobs.JobFailed
import pt.alinha.core.tenant.CorrelationContext
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.TenantId
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.util.UUID

/** What happened to one result entry. [Rejected] goes to the dead-letter stream; the others are just acknowledged. */
sealed interface ResultHandling {
    data object Applied : ResultHandling
    data class Ignored(val reason: String) : ResultHandling
    data class Rejected(val reason: String) : ResultHandling
}

/**
 * Applies one worker result (contracts/jobs/job-result.v1) to its outbox row. The message is untrusted: the job's
 * tenant, type and version come from the row, and a message that disagrees with it is rejected.
 */
@Component
class JobResultHandler(
    private val outbox: JobOutboxRepository,
    private val validator: JobSchemaValidator,
    private val tenants: TenantContext,
    private val correlation: CorrelationContext,
    private val tx: TransactionTemplate,
    private val audit: AuditTrail,
    private val events: ApplicationEventPublisher,
    private val json: JsonMapper,
    private val properties: JobsProperties,
) {
    fun handle(raw: String?): ResultHandling {
        val result = parse(raw) ?: return ResultHandling.Rejected("not a $RESULT_SCHEMA JSON object")
        try {
            validator.validate(RESULT_SCHEMA, result.document)
        } catch (e: IllegalArgumentException) {
            return ResultHandling.Rejected(e.message.orEmpty())
        }
        if (result.tenantId == NIL) return ResultHandling.Rejected("nil tenantId")
        // runAs refuses an open transaction, so the transaction starts inside it (same as DeadJobNotifier).
        return tenants.runAs(TenantId(result.tenantId)) { checkNotNull(tx.execute { apply(result) }) }
    }

    /**
     * The entry could not be applied after max-deliveries attempts (or max-total-deliveries deliveries) and is being
     * dead-lettered. Fail its job with [JobError.RESULT_NOT_APPLIED] so the plugin hears about it, as long as the
     * entry is a valid result for the job's current attempt. Nothing of the entry is stored: it may be what failed.
     * @return true when the job was failed.
     */
    fun failUnapplied(raw: String?, reason: String): Boolean {
        val result = parse(raw) ?: return false
        try {
            validator.validate(RESULT_SCHEMA, result.document)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (result.tenantId == NIL) return false
        return tenants.runAs(TenantId(result.tenantId)) {
            checkNotNull(
                tx.execute {
                    val job = outbox.lockForResult(result.jobId)
                    if (job == null || !result.matches(job) || job.status !in OPEN_STATUSES || job.attempt != result.attempt) {
                        false
                    } else {
                        val error = JobError.RESULT_NOT_APPLIED_ERROR
                        outbox.markFailed(job.id, "null", "${error.code}: $reason".take(MAX_ERROR_LENGTH))
                        record("job.failed", job, "errorCode" to error.code, "retryable" to false)
                        events.publishEvent(
                            JobFailed(TenantId(job.tenantId), correlation.current(), Instant.now(), job.id, job.type, job.requestedBy, job.attempt, error),
                        )
                        true
                    }
                },
            )
        }
    }

    private fun apply(result: WorkerResult): ResultHandling {
        val job = outbox.lockForResult(result.jobId) ?: return ResultHandling.Rejected("unknown job")
        if (!result.matches(job)) {
            return ResultHandling.Rejected("tenantId, type or version do not match the job")
        }
        // `pending` with the same attempt: the relay died after XADD, before marking the row `published`.
        if (job.status !in OPEN_STATUSES || job.attempt != result.attempt) {
            return ResultHandling.Ignored("job is ${job.status} at attempt ${job.attempt}")
        }
        when {
            result.completed -> complete(job, result)
            result.retryable && job.attempt < job.maxAttempts -> retry(job, result)
            else -> fail(job, result)
        }
        return ResultHandling.Applied
    }

    private fun complete(job: OutboxJob, result: WorkerResult) {
        outbox.markCompleted(job.id, json.writeValueAsString(result.document))
        record("job.completed", job)
        events.publishEvent(
            JobCompleted(TenantId(job.tenantId), correlation.current(), Instant.now(), job.id, job.type, job.requestedBy, job.attempt),
        )
    }

    private fun retry(job: OutboxJob, result: WorkerResult) {
        val relay = properties.relay
        outbox.scheduleRetry(job.id, result.errorText, publishBackoff(job.attempt, relay.backoffBase, relay.backoffMax).toMillis())
        record("job.retried", job, "errorCode" to result.error.code)
    }

    private fun fail(job: OutboxJob, result: WorkerResult) {
        outbox.markFailed(job.id, json.writeValueAsString(result.document), result.errorText)
        record("job.failed", job, "errorCode" to result.error.code, "retryable" to result.retryable)
        events.publishEvent(
            JobFailed(TenantId(job.tenantId), correlation.current(), Instant.now(), job.id, job.type, job.requestedBy, job.attempt, result.error),
        )
    }

    /** Error codes only: the worker's message is untrusted text and stays out of the audit trail. */
    private fun record(action: String, job: OutboxJob, vararg extra: Pair<String, Any?>) {
        audit.record(
            action = action,
            entityType = "job",
            entityId = job.id.toString(),
            details = mapOf("type" to job.type, "version" to job.version, "attempt" to job.attempt) + extra,
        )
    }

    private fun parse(raw: String?): WorkerResult? {
        val document = try {
            json.readValue<Map<String, Any?>?>(raw ?: return null)
        } catch (e: JacksonException) {
            null
        }
        return document?.let { WorkerResult(withoutNul(it) as Map<String, Any?>) }
    }

    /**
     * PostgreSQL text and jsonb cannot hold U+0000, so storing it would fail on every delivery and the job would
     * never finish. Replace it, in keys and values, before anything else reads the document.
     */
    private fun withoutNul(value: Any?): Any? = when (value) {
        is String -> value.replace(NUL, REPLACEMENT)
        is Map<*, *> -> value.entries.associate { (k, v) -> withoutNul(k) to withoutNul(v) }
        is List<*> -> value.map(::withoutNul)
        else -> value
    }

    /** Field access is safe only after the schema check. */
    private class WorkerResult(val document: Map<String, Any?>) {
        val jobId: UUID get() = UUID.fromString(document["jobId"] as String)
        val tenantId: UUID get() = UUID.fromString(document["tenantId"] as String)
        val type: String get() = document["type"] as String
        val version: Int get() = (document["version"] as Number).toInt()
        val attempt: Int get() = (document["attempt"] as Number).toInt()
        val completed: Boolean get() = document["status"] == "completed"
        val retryable: Boolean get() = document["retryable"] == true

        val error: JobError
            get() {
                val error = document["error"] as Map<*, *>
                return JobError(error["code"] as String, error["message"] as String)
            }

        val errorText: String get() = "${error.code}: ${error.message}".take(MAX_ERROR_LENGTH)

        fun matches(job: OutboxJob) = job.tenantId == tenantId && job.type == type && job.version == version
    }

    private companion object {
        const val RESULT_SCHEMA = "job-result.v1"
        const val MAX_ERROR_LENGTH = 500
        val OPEN_STATUSES = setOf("pending", "published")
        val NIL = UUID(0, 0)
        const val NUL = '\u0000'
        const val REPLACEMENT = '\uFFFD'
    }
}
