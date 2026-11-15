package pt.alinha.core.audit

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.UUID

/** Who did what, when, for the current tenant only (history view, US09). */
interface AuditHistory {
    fun find(filter: AuditFilter, pageable: Pageable): Page<AuditEntry>
}

data class AuditFilter(
    val entityType: String? = null,
    val entityId: String? = null,
    val actor: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)

data class AuditEntry(
    val id: UUID,
    val actor: String,
    val action: String,
    val entityType: String,
    val entityId: String,
    val details: Map<String, Any?>,
    val provenance: AiProvenance?,
    val occurredAt: Instant,
)
