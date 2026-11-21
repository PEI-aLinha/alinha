package pt.alinha.core.tenant.internal

import org.hibernate.boot.Metadata
import org.hibernate.boot.spi.BootstrapContext
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.event.service.spi.EventListenerRegistry
import org.hibernate.event.spi.EventType
import org.hibernate.event.spi.PreInsertEvent
import org.hibernate.event.spi.PreInsertEventListener
import org.hibernate.integrator.spi.Integrator
import pt.alinha.core.tenant.TenantOwnedEntity
import java.util.UUID

/**
 * Tenant of a JPA session opened with no tenant (e.g. Spring Data validating queries at startup).
 * Matches no row, so reads come back empty; [NoTenantWriteGuard] refuses writes.
 */
internal val NO_TENANT: UUID = UUID(0, 0)

/** Fails a tenant-owned insert from a session without a tenant, instead of storing it under [NO_TENANT]. */
internal object NoTenantWriteGuard : PreInsertEventListener, Integrator {
    override fun onPreInsert(event: PreInsertEvent): Boolean {
        check(event.entity !is TenantOwnedEntity || event.session.tenantIdentifierValue != NO_TENANT) {
            "Cannot write ${event.entity.javaClass.simpleName} without a tenant: use a tenant-scoped endpoint or TenantContext.runAs"
        }
        return false
    }

    override fun integrate(metadata: Metadata, context: BootstrapContext, sessionFactory: SessionFactoryImplementor) {
        sessionFactory.serviceRegistry
            .requireService(EventListenerRegistry::class.java)
            .appendListeners(EventType.PRE_INSERT, this)
    }
}
