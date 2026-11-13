package pt.alinha.core.tenant.internal

import org.hibernate.cfg.AvailableSettings
import org.hibernate.context.spi.CurrentTenantIdentifierResolver
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.stereotype.Component
import pt.alinha.core.tenant.TenantContext
import java.util.UUID

/** Feeds the request tenant to Hibernate's `@TenantId` filter. No tenant means the session does not open. */
@Component
class HibernateTenantResolver(private val tenantContext: TenantContext) :
    CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {

    override fun resolveCurrentTenantIdentifier(): UUID = tenantContext.current().value

    override fun validateExistingCurrentSessions(): Boolean = true

    override fun customize(hibernateProperties: MutableMap<String, Any>) {
        hibernateProperties[AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER] = this
    }
}