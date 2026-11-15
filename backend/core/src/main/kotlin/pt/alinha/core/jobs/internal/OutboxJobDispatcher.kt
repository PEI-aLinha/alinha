package pt.alinha.core.jobs.internal

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pt.alinha.core.audit.AuditTrail
import pt.alinha.core.jobs.JobDispatcher
import pt.alinha.core.jobs.JobRequest
import pt.alinha.core.tenant.TenantContext
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

@Component
class OutboxJobDispatcher(
    private val outbox: JobOutboxRepository,
    private val validator: JobSchemaValidator,
    private val tenants: TenantContext,
    private val audit: AuditTrail,
    private val json: JsonMapper,
    private val properties: JobsProperties,
) : JobDispatcher {

    /** MANDATORY: the job commits or rolls back with the caller's write (RNF02). */
    @Transactional(propagation = Propagation.MANDATORY)
    override fun dispatch(request: JobRequest): UUID {
        val tenantId = tenants.current().value
        val jobId = UUID.randomUUID()
        val envelope = request.payload + mapOf(
            "jobId" to jobId.toString(),
            "type" to request.type,
            "tenantId" to tenantId.toString(),
            "requestedAt" to Instant.now().toString(),
        )
        validator.validate(JobSchemaValidator.envelopeSchema(request.type, request.version), envelope)

        val inserted = outbox.insert(
            NewOutboxJob(
                id = jobId,
                tenantId = tenantId,
                type = request.type,
                version = request.version,
                requestedBy = request.requestedBy,
                envelope = json.writeValueAsString(envelope),
                maxAttempts = properties.defaultMaxAttempts,
                idempotencyKey = request.idempotencyKey,
            ),
        ) ?: return existingJob(tenantId, request)

        audit.record(
            action = "job.dispatched",
            entityType = "job",
            entityId = inserted.toString(),
            details = mapOf("type" to request.type, "version" to request.version, "requestedBy" to request.requestedBy),
        )
        return inserted
    }

    /** Only reached on an idempotency conflict, so the key is set and the row exists. */
    private fun existingJob(tenantId: UUID, request: JobRequest): UUID {
        val key = checkNotNull(request.idempotencyKey) { "Outbox insert skipped without an idempotency key" }
        return checkNotNull(outbox.findIdByIdempotencyKey(tenantId, request.type, key)) {
            "Idempotency conflict but no job found for type ${request.type}"
        }
    }
}
