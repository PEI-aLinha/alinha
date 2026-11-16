package pt.alinha.core.tenant

import pt.alinha.pluginapi.TenantId

/**
 * Creates the organization (tenant) of the person who is registering it (ADR 0004). Call it from a
 * [TenantAgnostic] endpoint, outside any transaction.
 *
 * The caller must hold the `registrant` role and no tenant. [block] runs as the new tenant inside one
 * transaction, so everything it writes (and its audit entries) belongs to that tenant. When the transaction is
 * ready to commit, the account gets the tenant and the `responsible` role in the identity provider. Any failure
 * rolls everything back. The caller's token stays stale until the client refreshes it.
 *
 * @throws org.springframework.web.server.ResponseStatusException 409 when the account already has, or already
 *   created, an organization.
 * @throws org.springframework.security.access.AccessDeniedException when the caller is not a `registrant`.
 */
interface TenantProvisioning {
    fun <T> provisionForCurrentUser(block: (TenantId) -> T): T
}
