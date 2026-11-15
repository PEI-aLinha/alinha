package pt.alinha.core.audit

import java.time.Instant
/**
 * Append-only. Every write is recorded here, in the caller's transaction (RNF04): call it from
 * inside your @Transactional service method. Outside a transaction it throws.
 *
 * The tenant and the actor come from the request (TenantContext / token), never from the caller.
 * [details] must never contain secrets or document content; ids and changed field names only.
 * Exception to append-only: personal data is anonymised after 1 year by a separate role (RNF15).
 */
interface AuditTrail {
    fun record(
        action: String,
        entityType: String,
        entityId: String,
        details: Map<String, Any?> = emptyMap(),
        provenance: AiProvenance? = null,
    )
}

/** Provenance of AI-generated content (RNF 14), Use AiDraft.provenance() **/
data class AiProvenance(
    val model: String,
    val promptVersion: String,
    val sources: List<String>,
    val generatedAt: Instant,
)