package pt.alinha.fixture.tenantbypass

import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

// Known tenant-filter bypasses. TenantFilterBypassTest checks its rules against these (positive control).
// They live in test sources, so the real rule check, which skips tests, never sees them.

class JdbcTemplateUser(private val jdbc: JdbcTemplate) {
    fun count(): Int? = jdbc.queryForObject("select 1", Int::class.java)
}

interface NativeQueryRepository : Repository<Any, UUID> {
    @Query("select 1", nativeQuery = true)
    fun one(): Int
}

class EntityManagerNativeQuery(private val em: EntityManager) {
    fun run(): Any? = em.createNativeQuery("select 1").singleResult
}

class SessionDoWork(private val session: Session) {
    fun run() = session.doWork { }
}

class SessionDoReturningWork(private val session: Session) {
    fun run(): Int = session.doReturningWork { 1 }
}
