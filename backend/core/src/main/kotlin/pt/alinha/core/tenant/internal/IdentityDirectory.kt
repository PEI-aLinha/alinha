package pt.alinha.core.tenant.internal

import pt.alinha.pluginapi.TenantId

/**
 * Accounts in the identity provider, without naming the vendor (ADR 0004). Accounts are identified by the
 * token's `sub`. Internal: plugins never manage accounts. Later work (analyst invitations, consultant
 * assignment, actor names) extends this interface instead of adding another admin client.
 */
interface IdentityDirectory {
    /** Gives the account the tenant and the Designated Officer role, and removes the registrant role. */
    fun assignTenant(subject: String, tenantId: TenantId)

    /** Undoes [assignTenant]; idempotent, so it can run after a partial assignment. */
    fun revokeTenant(subject: String, tenantId: TenantId)
}
