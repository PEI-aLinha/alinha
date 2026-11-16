package pt.alinha.core.tenant.internal

import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.TenantId

@Component
class JwtTenantContext : TenantContext {
    private val scoped = ThreadLocal<TenantId>()

    override fun current(): TenantId =
        currentOrNull() ?: error("No tenant: use a tenant-scoped endpoint or TenantContext.runAs")

    internal fun currentOrNull(): TenantId? =
        scoped.get() ?: requestTenant()

    private fun requestTenant(): TenantId? =
        RequestContextHolder.getRequestAttributes()
            ?.getAttribute(TENANT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) as? TenantId

    override fun <T> runAs(tenantId: TenantId, block: () -> T): T {
        // Any HTTP request, tenant-agnostic ones included: otherwise a handler could reach any tenant's data.
        check(RequestContextHolder.getRequestAttributes() == null) {
            "runAs is not allowed inside an HTTP request: it would let the request reach another tenant"
        }
        return bind(tenantId, block)
    }

    /**
     * Binds a tenant that was **just created** for the caller, inside its own request (ADR 0004). Safe because the
     * tenant has no data yet and belongs to the caller. Only [TenantProvisioner] may use it.
     */
    internal fun <T> runAsNewTenant(tenantId: TenantId, block: () -> T): T {
        check(requestTenant() == null) { "Cannot provision a tenant from a tenant-scoped request" }
        return bind(tenantId, block)
    }

    private fun <T> bind(tenantId: TenantId, block: () -> T): T {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "runAs must start outside a transaction: the open JPA session keeps its tenant"
        }
        val previous = scoped.get()
        scoped.set(tenantId)
        try {
            return block()
        } finally {
            if (previous == null) scoped.remove() else scoped.set(previous)
        }
    }
}