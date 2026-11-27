package pt.alinha.core.tenant.internal

import pt.alinha.pluginapi.TenantId

/** An account of the identity provider that belongs to a tenant. */
data class DirectoryMember(val subject: String, val email: String?, val name: String?, val roles: Set<String>)

/**
 * Members of an organization in the identity provider (ADR 0007): joining an existing tenant by invitation, leaving
 * it, and listing who is in it. Internal, like [IdentityDirectory], and implemented by the same Keycloak client. A
 * separate interface so that the existing [IdentityDirectory] fakes in plugin tests keep compiling.
 *
 * Joining works like [IdentityDirectory.assignTenant] with another role: it sets `tenant_id`, maps [role] and removes
 * `registrant`. Leaving undoes it and gives `registrant` back, so the account is again a new account of its own.
 */
interface IdentityMembership {
    fun joinTenant(subject: String, tenantId: TenantId, role: String)

    /** Undoes [joinTenant]; idempotent, and does nothing if the account is no longer in [tenantId]. */
    fun leaveTenant(subject: String, tenantId: TenantId, role: String)

    fun members(tenantId: TenantId): List<DirectoryMember>

    /** Ends every session of the account, so a removed member cannot refresh a token that still names the tenant. */
    fun endSessions(subject: String)
}
