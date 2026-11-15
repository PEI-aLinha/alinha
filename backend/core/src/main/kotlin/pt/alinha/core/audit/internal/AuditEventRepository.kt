package pt.alinha.core.audit.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.UUID

interface AuditEventRepository :
    JpaRepository<AuditEventEntity, UUID>,
    JpaSpecificationExecutor<AuditEventEntity>