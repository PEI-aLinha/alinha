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
        scoped.get()
            ?: RequestContextHolder.getRequestAttributes()
                ?.getAttribute(TENANT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) as? TenantId

    override fun <T> runAs(tenantId: TenantId, block: () -> T): T {
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