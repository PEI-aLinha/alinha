package pt.alinha.pluginapi

import java.time.Instant
import java.util.UUID

/**
 * How much a piece of evidence can be trusted (RF38). The Compliance Engine weighs control status by it.
 * Ordered from weakest to strongest: compare with [isAtLeast], never by name.
 */
enum class EvidenceStrength {
    /** Declared by the organisation (questionnaire answer) and not checked. */
    SELF_REPORTED,

    /** A document was reviewed by a consultant. */
    DOCUMENT_VERIFIED,

    /** Read from the organisation's systems by a provider, once. */
    TECHNICALLY_VERIFIED,

    /** Read from the organisation's systems by a provider on a schedule, and still current. */
    CONTINUOUSLY_VERIFIED,
    ;

    fun isAtLeast(other: EvidenceStrength): Boolean = ordinal >= other.ordinal
}

/** What a piece of evidence says about its controls. Consumers must read it: a failed check is evidence too. */
enum class EvidenceOutcome {
    /** The check passed: the evidence supports the controls. */
    SATISFIED,

    /** The check failed: the evidence shows a gap in the controls. */
    NOT_SATISFIED,
}

/**
 * A provider stored one piece of evidence for one or more catalogue controls. Published by provider plugins
 * (code-repository, external-surface) in the transaction that stored it; consumed by the assessment and reports.
 *
 * Ids only (docs/events.md): read the evidence itself through the publishing plugin's `api`.
 *
 * @property provider the publishing plugin's manifest id
 * @property evidenceId the evidence row in the provider's own schema
 * @property controlIds canonical control ids from the catalogue (e.g. `PR.SP-4-S`); never empty
 * @property outcome whether the evidence supports the controls or shows a gap; [strength] says how much to trust it
 */
data class EvidenceCollected(
    override val tenantId: TenantId,
    override val correlationId: UUID,
    override val occurredAt: Instant,
    val provider: String,
    val evidenceId: UUID,
    val controlIds: Set<String>,
    val outcome: EvidenceOutcome,
    val strength: EvidenceStrength,
) : DomainEvent {
    init {
        require(provider.isNotBlank()) { "provider is blank" }
        require(controlIds.isNotEmpty()) { "evidence must map to at least one control" }
    }
}
