package pt.alinha.core.tenant

import pt.alinha.pluginapi.TenantId

/**
 * Tenant of the current request (RNF03). ALWAYS taken from the token, never from body/query:
 * client users have one tenant (`tenant_id` claim); a consultant picks one per request with
 * the `X-Alinha-Tenant` header, restricted to the organizations in the `tenants` claim.
 */
interface TenantContext {
    /** @throws IllegalStateException on a [TenantAgnostic] endpoint or outside a request / [runAs]. */
    fun current(): TenantId

    /**
     * Runs [block] as [tenantId], for work without a request (event listeners, jobs).
     * Must start OUTSIDE a transaction: a JPA session keeps the tenant it was opened with.
     */
    fun <T> runAs(tenantId: TenantId, block: () -> T): T
}
