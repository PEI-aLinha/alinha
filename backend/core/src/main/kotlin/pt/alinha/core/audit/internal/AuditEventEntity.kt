package pt.alinha.core.audit.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pt.alinha.core.audit.AiProvenance
import pt.alinha.core.audit.AuditEntry
import pt.alinha.core.tenant.TenantOwnedEntity
import java.time.Instant
import java.util.UUID

/** Hibernate stamps tenant_id from TenantContext and filters every query by it (TenantOwnedEntity). */
@Entity
@Immutable
@Table(schema = "audit", name = "audit_event")
class AuditEventEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, updatable = false)
    val actor: String,

    @Column(nullable = false, updatable = false)
    val action: String,

    @Column(name = "entity_type", nullable = false, updatable = false)
    val entityType: String,

    @Column(name = "entity_id", nullable = false, updatable = false)
    val entityId: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    val details: Map<String, Any?> = emptyMap(),

    @Column(name = "ai_model", updatable = false)
    val aiModel: String? = null,

    @Column(name = "ai_prompt_version", updatable = false)
    val aiPromptVersion: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_sources", updatable = false, columnDefinition = "jsonb")
    val aiSources: List<String>? = null,

    @Column(name = "ai_generated_at", updatable = false)
    val aiGeneratedAt: Instant? = null,

    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: Instant = Instant.now(),
) : TenantOwnedEntity() {

    fun toEntry() = AuditEntry(
        id = id,
        actor = actor,
        action = action,
        entityType = entityType,
        entityId = entityId,
        details = details,
        provenance = aiModel?.let {
            AiProvenance(it, checkNotNull(aiPromptVersion), aiSources.orEmpty(), checkNotNull(aiGeneratedAt))
        },
        occurredAt = occurredAt,
    )
}
