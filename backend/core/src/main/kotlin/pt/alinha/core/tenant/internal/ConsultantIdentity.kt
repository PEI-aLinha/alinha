package pt.alinha.core.tenant.internal

import pt.alinha.pluginapi.TenantId

/**
 * Consultants in the identity provider (FR04): the pool for [ConsultantAssigner]. A separate interface from
 * [IdentityDirectory] so plugins' test applications, which fake that one, are not affected.
 */
interface ConsultantIdentity {
    /** Every enabled consultant, with the organizations in their `tenants` attribute. */
    fun consultants(): List<Consultant>

    /**
     * Adds [tenantId] to the consultant's `tenants`. Idempotent: an organization already there changes nothing.
     *
     * @throws ConsultantNotEligibleException when [subject] is not an enabled consultant (disabled, deleted or removed
     *   from the group since [consultants]), so the caller can try another one. Any other exception is a failure of the
     *   identity provider and must not be read as "try the next one".
     */
    fun assign(subject: String, tenantId: TenantId)
}

/** [ConsultantIdentity.assign]'s "this account cannot take organizations": the only failure the caller skips. */
class ConsultantNotEligibleException(subject: String) : RuntimeException("Not an enabled consultant: $subject")

/** A consultant: the token's `sub` and the organizations in their `tenants` claim. */
data class Consultant(val subject: String, val tenants: Set<TenantId>)
