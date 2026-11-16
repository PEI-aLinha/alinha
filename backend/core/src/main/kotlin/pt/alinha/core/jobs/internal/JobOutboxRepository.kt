package pt.alinha.core.jobs.internal

import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

data class NewOutboxJob(
    val id: UUID,
    val tenantId: UUID,
    val type: String,
    val version: Int,
    val requestedBy: String,
    val envelope: String,
    val maxAttempts: Int,
    val idempotencyKey: String?,
)

data class OutboxJob(
    val id: UUID,
    val tenantId: UUID,
    val type: String,
    val version: Int,
    val requestedBy: String,
    val envelope: String,
    val attempt: Int,
    val publishFailures: Int,
    val status: String,
    val maxAttempts: Int,
)

/** A job as its tenant sees it; [document] is the worker's last result (job-result.v1) once the job has finished. */
data class OutboxJobState(
    val id: UUID,
    val type: String,
    val status: String,
    val attempt: Int,
    val document: String?,
    val finishedAt: Instant?,
)

/**
 * Raw SQL on purpose: the relay reads across tenants, so tenant_id is stamped/filtered by hand here
 * (core only, see TenantFilterBypassTest and docs/tenancy.md).
 */
@Repository
class JobOutboxRepository(private val jdbc: JdbcClient) {

    /** @return the id, or null when (tenant, type, requestedBy, idempotencyKey) already exists. */
    fun insert(job: NewOutboxJob): UUID? =
        jdbc.sql(
            """
              insert into core.job_outbox (id, tenant_id, type, version, requested_by, envelope, max_attempts, idempotency_key)
              values (:id, :tenant, :type, :version, :requestedBy, cast(:envelope as jsonb), :maxAttempts, :key)
              on conflict on constraint job_outbox_idempotency do nothing
              returning id
              """.trimIndent(),
        )
            .param("id", job.id)
            .param("tenant", job.tenantId)
            .param("type", job.type)
            .param("version", job.version)
            .param("requestedBy", job.requestedBy)
            .param("envelope", job.envelope)
            .param("maxAttempts", job.maxAttempts)
            .param("key", job.idempotencyKey)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    fun findIdByIdempotencyKey(tenantId: UUID, type: String, requestedBy: String, key: String): UUID? =
        jdbc.sql(
            """
              select id from core.job_outbox
              where tenant_id = :tenant and type = :type and requested_by = :requestedBy and idempotency_key = :key
              """.trimIndent(),
        )
            .param("tenant", tenantId)
            .param("type", type)
            .param("requestedBy", requestedBy)
            .param("key", key)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    /** Locks due pending jobs; rows another relay holds are skipped. Must run inside a transaction. */
    fun lockDuePending(limit: Int): List<OutboxJob> =
        jdbc.sql(
            """
              $SELECT_JOB
              where status = 'pending' and next_attempt_at <= now()
              order by next_attempt_at, created_at
              limit :limit
              for update skip locked
              """.trimIndent(),
        ).param("limit", limit).query(::mapJob).list()

    fun markPublished(id: UUID) {
        jdbc.sql("update core.job_outbox set status = 'published', published_at = now(), last_error = null where id = :id")
            .param("id", id)
            .update()
    }

    /** Uses the database clock, the same one lockDuePending compares against. */
    fun markPublishFailed(id: UUID, error: String, retryInMillis: Long, dead: Boolean) {
        jdbc.sql(
            """
              update core.job_outbox
              set publish_failures = publish_failures + 1,
                  last_error       = :error,
                  next_attempt_at  = now() + :delay * interval '1 millisecond',
                  status           = :status
              where id = :id
              """.trimIndent(),
        )
            .param("id", id)
            .param("error", error)
            .param("delay", retryInMillis)
            .param("status", if (dead) "dead" else "pending")
            .update()
    }

    /** Redis outage: retry later without counting a failure, so the row never ends `dead` because of it. */
    fun deferPublish(id: UUID, error: String, retryInMillis: Long) {
        jdbc.sql(
            """
              update core.job_outbox
              set last_error      = :error,
                  next_attempt_at = now() + :delay * interval '1 millisecond'
              where id = :id
              """.trimIndent(),
        )
            .param("id", id)
            .param("error", error)
            .param("delay", retryInMillis)
            .update()
    }

    fun findDeadUnnotified(limit: Int): List<OutboxJob> =
        jdbc.sql("$SELECT_JOB where status = 'dead' and finished_at is null order by created_at limit :limit")
            .param("limit", limit)
            .query(::mapJob)
            .list()

    /** @return true for exactly one caller, so JobFailed is emitted once even with several instances. */
    fun markNotified(id: UUID): Boolean =
        jdbc.sql("update core.job_outbox set finished_at = now() where id = :id and status = 'dead' and finished_at is null")
            .param("id", id)
            .update() == 1

    /**
     * Locks one job to apply a worker result. Waits (no SKIP LOCKED) while the relay holds the row, so a result that
     * arrives before the relay commits `published` is applied after it, not lost. Must run inside a transaction.
     */
    fun lockForResult(id: UUID): OutboxJob? =
        jdbc.sql("$SELECT_JOB where id = :id for update").param("id", id).query(::mapJob).optional().orElse(null)

    /** Stores the worker's result [document] (job-result.v1) and ends the job. */
    fun markCompleted(id: UUID, document: String) {
        finish(id, "completed", document, error = null)
    }

    fun markFailed(id: UUID, document: String, error: String) {
        finish(id, "failed", document, error)
    }

    private fun finish(id: UUID, status: String, document: String, error: String?) {
        jdbc.sql(
            """
              update core.job_outbox
              set status      = :status,
                  result      = cast(:document as jsonb),
                  last_error  = :error,
                  finished_at = now()
              where id = :id
              """.trimIndent(),
        )
            .param("id", id)
            .param("status", status)
            .param("document", document)
            .param("error", error)
            .update()
    }

    /**
     * Back to `pending` with the next attempt number; the relay publishes it again after the delay. Publish failures
     * count per attempt, so the new one starts from zero.
     */
    fun scheduleRetry(id: UUID, error: String, retryInMillis: Long) {
        jdbc.sql(
            """
              update core.job_outbox
              set status           = 'pending',
                  attempt          = attempt + 1,
                  publish_failures = 0,
                  published_at     = null,
                  last_error       = :error,
                  next_attempt_at  = now() + :delay * interval '1 millisecond'
              where id = :id
              """.trimIndent(),
        )
            .param("id", id)
            .param("error", error)
            .param("delay", retryInMillis)
            .update()
    }

    /** One cheap round trip. @return false when the database cannot be reached. */
    fun isReachable(): Boolean = try {
        jdbc.sql("select 1").query(Int::class.javaObjectType).single()
        true
    } catch (e: DataAccessException) {
        false
    }

    /** Filtered by [tenantId] here: this is the read plugins reach through JobResults. */
    fun findState(id: UUID, tenantId: UUID): OutboxJobState? =
        jdbc.sql(
            """
              select id, type, status, attempt, result::text as document, finished_at
              from core.job_outbox
              where id = :id and tenant_id = :tenant
              """.trimIndent(),
        )
            .param("id", id)
            .param("tenant", tenantId)
            .query { rs, _ ->
                OutboxJobState(
                    id = rs.getObject("id", UUID::class.java),
                    type = rs.getString("type"),
                    status = rs.getString("status"),
                    attempt = rs.getInt("attempt"),
                    document = rs.getString("document"),
                    finishedAt = rs.getTimestamp("finished_at")?.toInstant(),
                )
            }
            .optional()
            .orElse(null)

    private fun mapJob(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = OutboxJob(
        id = rs.getObject("id", UUID::class.java),
        tenantId = rs.getObject("tenant_id", UUID::class.java),
        type = rs.getString("type"),
        version = rs.getInt("version"),
        requestedBy = rs.getString("requested_by"),
        envelope = rs.getString("envelope"),
        attempt = rs.getInt("attempt"),
        publishFailures = rs.getInt("publish_failures"),
        status = rs.getString("status"),
        maxAttempts = rs.getInt("max_attempts"),
    )

    private companion object {
        const val SELECT_JOB =
            "select id, tenant_id, type, version, requested_by, envelope::text as envelope, attempt, publish_failures, " +
                    "status, max_attempts from core.job_outbox"
    }
}
