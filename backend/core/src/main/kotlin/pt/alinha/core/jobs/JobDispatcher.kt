package pt.alinha.core.jobs

import java.util.UUID

/**
 * Dispatches jobs to the Python workers: PostgreSQL outbox → Redis Streams (RNF02). See docs/jobs.md.
 */
interface JobDispatcher {
    /**
     * Writes the job to the outbox in the caller's transaction (MANDATORY), so it is published only if that commits.
     *
     * @return the new jobId, or the existing one when [JobRequest.idempotencyKey] was already used for this
     *   tenant and type (the second payload is ignored).
     * @throws IllegalArgumentException if the envelope does not match contracts/jobs/<type>.v<version>.schema.json
     */
    fun dispatch(request: JobRequest): UUID
}

/**
 * [payload] holds only the type-specific fields: the core adds jobId, type, tenantId and requestedAt.
 * Never put credentials in it; pass a connectionRef (TenantSecretStore).
 * [requestedBy] is the calling plugin's id. It is not yet bound to the caller (#60): never take it from a request.
 */
data class JobRequest(
    val type: String,
    val payload: Map<String, Any?>,
    val requestedBy: String,
    val version: Int = 1,
    val idempotencyKey: String? = null,
) {
    init {
        require(TYPE.matches(type)) { "Invalid job type '$type'" }
        require(version >= 1) { "version must be >= 1" }
        require(PLUGIN_ID.matches(requestedBy)) { "requestedBy must be a plugin id" }
        require(idempotencyKey == null || (idempotencyKey.isNotBlank() && idempotencyKey.length <= MAX_KEY_LENGTH)) {
            "idempotencyKey must be 1..$MAX_KEY_LENGTH characters"
        }
        require(payload.keys.none { it in STAMPED_FIELDS }) { "payload must not set $STAMPED_FIELDS: the core does" }
    }

    companion object {
        private val TYPE = Regex("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$")
        private val PLUGIN_ID = Regex("^[a-z][a-z0-9-]*$")
        private const val MAX_KEY_LENGTH = 200
        val STAMPED_FIELDS = setOf("jobId", "type", "tenantId", "requestedAt")
    }
}
