package pt.alinha.core.tenant

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import org.hibernate.annotations.TenantId
import java.util.UUID

@MappedSuperclass
abstract class TenantOwnedEntity {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    var tenantId: UUID? = null
        protected set
}