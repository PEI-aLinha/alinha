package pt.alinha.core.audit

/**
 * Append-only. Every write is recorded here, in the SAME transaction (RNF04).
 * Exception: personal data in the audit trail is anonymised after 1 year (RNF15).
 */
interface AuditTrail {
    fun record(action: String, entityType: String, entityId: String, details: Map<String, Any?> = emptyMap())
}
