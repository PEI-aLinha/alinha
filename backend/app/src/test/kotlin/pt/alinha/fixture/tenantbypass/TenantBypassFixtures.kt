package pt.alinha.fixture.tenantbypass

import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import javax.sql.DataSource

// Known tenant-filter bypasses. TenantFilterBypassTest checks its rules against these (positive control).
// They live in test sources, so the real rule check, which skips tests, never sees them.

class JdbcTemplateUser(private val jdbc: JdbcTemplate) {
    fun count(): Int? = jdbc.queryForObject("select 1", Int::class.java)
}

class JdbcOperationsUser(private val jdbc: JdbcOperations) {
    fun count(): Int? = jdbc.queryForObject("select 1", Int::class.java)
}

class JdbcClientUser(private val jdbc: JdbcClient) {
    fun count(): Int = jdbc.sql("select 1").query(Int::class.java).single()
}

class DataSourceUser(private val dataSource: DataSource) {
    fun open(): Any = dataSource.connection
}

class ConnectionUser(private val connection: Connection) {
    fun run(): Boolean = connection.createStatement().execute("select 1")
}

class StatementUser(private val statement: PreparedStatement) {
    fun run(): Boolean = statement.execute()
}

class DriverManagerUser {
    fun open(): Any = DriverManager.getConnection("jdbc:postgresql://localhost/alinha")
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
