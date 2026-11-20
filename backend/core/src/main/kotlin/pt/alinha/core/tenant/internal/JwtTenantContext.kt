package pt.alinha.core.tenant.internal

import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.DomainEvent
import pt.alinha.pluginapi.TenantId
import java.util.UUID

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
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "runAs must start outside a transaction: the open JPA session keeps its tenant"
        }
        // Any HTTP request, tenant-agnostic ones included: otherwise a handler could reach any tenant's data.
        check(RequestContextHolder.getRequestAttributes() == null) {
            "runAs is not allowed inside an HTTP request: it would let the request reach another tenant"
        }
        val previous = scoped.get()
        scoped.set(tenantId)
        try {
            // A job keeps one correlation id for all its work; runAs(event) replaces it with the event's.
            return CorrelationScope.runWith(CorrelationScope.currentOrNull() ?: UUID.randomUUID(), block)
        } finally {
            if (previous == null) scoped.remove() else scoped.set(previous)
        }
    }

    override fun <T> runAs(event: DomainEvent, block: () -> T): T =
        runAs(event.tenantId) { CorrelationScope.runWith(event.correlationId, block) }
}