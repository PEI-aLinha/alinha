package pt.alinha.core.audit.internal

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pt.alinha.core.audit.AuditEntry
import pt.alinha.core.audit.AuditFilter
import pt.alinha.core.audit.AuditHistory
import java.time.Instant

@Component
class JpaAuditHistory(private val events: AuditEventRepository) : AuditHistory {

    @Transactional(readOnly = true)
    override fun find(filter: AuditFilter, pageable: Pageable): Page<AuditEntry> =
        events.findAll(Specification.allOf(specs(filter)), pageable).map { it.toEntry() }

    private fun specs(f: AuditFilter): List<Specification<AuditEventEntity>> = listOfNotNull(
        f.entityType?.let { v -> eq("entityType", v) },
        f.entityId?.let { v -> eq("entityId", v) },
        f.actor?.let { v -> eq("actor", v) },
        f.from?.let { v -> Specification { root, _, cb -> cb.greaterThanOrEqualTo(root.get<Instant>("occurredAt"), v) } },
        f.to?.let { v -> Specification { root, _, cb -> cb.lessThan(root.get<Instant>("occurredAt"), v) } },
    )

    private fun eq(field: String, value: String) =
        Specification<AuditEventEntity> { root, _, cb -> cb.equal(root.get<String>(field), value) }
}