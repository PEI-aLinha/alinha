package pt.alinha.core.audit

/** Append-only. Toda a escrita regista aqui, na MESMA transação (RNF05). */
interface AuditTrail {
    fun record(action: String, entityType: String, entityId: String, details: Map<String, Any?> = emptyMap())
}
