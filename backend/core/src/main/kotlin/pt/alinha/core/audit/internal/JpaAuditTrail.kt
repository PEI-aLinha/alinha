package pt.alinha.core.audit.internal

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pt.alinha.core.audit.AiProvenance
import pt.alinha.core.audit.AuditTrail

@Component
class JpaAuditTrail(
    private val em: EntityManager,
    private val actor: AuditActor,
) : AuditTrail {

    /** MANDATORY: the entry commits or rolls back with the business write (RNF04). */
    @Transactional(propagation = Propagation.MANDATORY)
    override fun record(
        action: String,
        entityType: String,
        entityId: String,
        details: Map<String, Any?>,
        provenance: AiProvenance?,
    ) {
        require(action.isNotBlank() && entityType.isNotBlank() && entityId.isNotBlank()) {
            "action, entityType and entityId are required"
        }
        // persist, not repository.save: the id is assigned, and save() would merge (SELECT then INSERT).
        em.persist(
            AuditEventEntity(
                actor = actor.current(),
                action = action,
                entityType = entityType,
                entityId = entityId,
                details = details,
                aiModel = provenance?.model,
                aiPromptVersion = provenance?.promptVersion,
                aiSources = provenance?.sources,
                aiGeneratedAt = provenance?.generatedAt,
            ),
        )
    }
}
