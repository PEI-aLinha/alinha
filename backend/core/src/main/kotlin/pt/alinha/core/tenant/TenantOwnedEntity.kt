package pt.alinha.core.tenant

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import org.hibernate.annotations.TenantId
import java.util.UUID

/**
 * Base class for every plugin entity holding tenant data. Hibernate fills [tenantId] from
 * [TenantContext] on insert and adds `tenant_id = ?` to every query, so a plugin cannot
 * read or write another tenant's rows through JPA.
 */
@MappedSuperclass
abstract class TenantOwnedEntity {
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    var tenantId: UUID? = null
        protected set
}