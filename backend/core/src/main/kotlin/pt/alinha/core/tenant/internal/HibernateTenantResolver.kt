package pt.alinha.core.tenant.internal

import org.hibernate.cfg.AvailableSettings
import org.hibernate.context.spi.CurrentTenantIdentifierResolver
import org.hibernate.jpa.boot.spi.IntegratorProvider
import org.hibernate.jpa.boot.spi.JpaSettings
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Feeds the request tenant to Hibernate's `@TenantId` filter. A session without tenant (e.g. Spring Data
 * validating `@Query` at startup) gets [NO_TENANT]: it reads nothing and [NoTenantWriteGuard] blocks writes.
 */
@Component
class HibernateTenantResolver(private val tenantContext: JwtTenantContext) :
    CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {

    override fun resolveCurrentTenantIdentifier(): UUID = tenantContext.currentOrNull()?.value ?: NO_TENANT

    override fun validateExistingCurrentSessions(): Boolean = true

    override fun customize(hibernateProperties: MutableMap<String, Any>) {
        hibernateProperties[AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER] = this
        hibernateProperties[JpaSettings.INTEGRATOR_PROVIDER] = IntegratorProvider { listOf(NoTenantWriteGuard) }
    }
}