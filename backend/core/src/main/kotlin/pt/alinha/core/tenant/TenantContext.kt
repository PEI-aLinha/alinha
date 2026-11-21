package pt.alinha.core.tenant

import pt.alinha.pluginapi.DomainEvent
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
     * Must start OUTSIDE a transaction (a JPA session keeps the tenant it was opened with) and outside any
     * HTTP request, so a handler cannot switch tenant.
     * The whole block shares one new correlation id (see [CorrelationContext]).
     */
    fun <T> runAs(tenantId: TenantId, block: () -> T): T

    /**
     * Runs [block] as the event's tenant, with the event's correlation id, so events published inside [block]
     * continue the same chain. Use it in event listeners; same rules as the other [runAs].
     */
    fun <T> runAs(event: DomainEvent, block: () -> T): T
}
