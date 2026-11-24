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
     * @throws IllegalArgumentException when [subject] is not an enabled consultant.
     */
    fun assign(subject: String, tenantId: TenantId)
}

/** A consultant: the token's `sub` and the organizations in their `tenants` claim. */
data class Consultant(val subject: String, val tenants: Set<TenantId>)
