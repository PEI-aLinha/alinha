package pt.alinha.core.tenant

/**
 * Gives a new organization a consultant (FR04, organization#5). The rule is global (it compares every consultant's
 * organizations), so it lives in the core: plugins only see their own tenant's data.
 */
interface ConsultantAssignment {
    /**
     * Assigns the current tenant ([TenantContext.current]) to the consultant with the fewest organizations. On a tie,
     * the one whose last automatic assignment is oldest (never assigned first), then the lowest `sub`, so the choice
     * is repeatable. The consultant gets the organization in their `tenants` claim on their next token refresh
     * (ADR 0002). Records `tenant.consultant_assigned` in the audit trail.
     *
     * Idempotent: when the organization already has a consultant (assigned earlier, or by an admin in the identity
     * provider), that one is returned with [AssignedConsultant.newlyAssigned] false and nothing else changes, so an
     * event listener can be retried. Concurrent calls are serialised, so two registrations at once cannot both pick
     * the same "least busy" consultant.
     *
     * Call it inside a transaction, as the tenant (`TenantContext.runAs` in an event listener). It calls the identity
     * provider before returning (timeouts about 10 s per call); a failure there throws and the transaction must roll
     * back.
     *
     * @return the consultant, or null when there is none (logged as a warning; an admin assigns one by hand).
     */
    fun assignConsultant(): AssignedConsultant?
}

/** [subject] is the consultant's token `sub`; never their name or email (those stay in the identity provider). */
data class AssignedConsultant(val subject: String, val newlyAssigned: Boolean)
