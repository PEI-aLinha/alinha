package pt.alinha.core.audit.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/** RNF04 at the database level: grants bind the runtime role, the trigger binds everyone else. */
@SpringBootTest(classes = [AuditTestApplication::class])
class AuditAppendOnlyTest {
    companion object {
        /** Throwaway login for the test container, new on every run. */
        private val APP_PASSWORD = UUID.randomUUID().toString()
        private val CHANGES = listOf(
            "update audit.audit_event set action = 'tampered'",
            "delete from audit.audit_event",
            "truncate audit.audit_event",
        )

        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) = AuditDatabase.register(registry)
    }

    @Autowired private lateinit var jdbc: JdbcTemplate

    /** Row triggers only fire when a row matches, so there is always at least one. */
    @BeforeEach
    fun seed() {
        jdbc.update(
            "insert into audit.audit_event (id, tenant_id, actor, action, entity_type, entity_id) " +
                "values (?, ?, 'system', 'seed', 'fixture', 'seed')",
            UUID.randomUUID(), UUID.randomUUID(),
        )
    }

    private fun asRuntimeRole(block: (java.sql.Connection) -> Unit) {
        jdbc.execute("alter role alinha_app login password '$APP_PASSWORD'")
        DriverManager.getConnection(AuditDatabase.postgres.jdbcUrl, "alinha_app", APP_PASSWORD).use(block)
    }

    @Test
    fun `runtime role can insert and read`() = asRuntimeRole { c ->
        c.createStatement().use {
            it.executeUpdate(
                "insert into audit.audit_event (id, tenant_id, actor, action, entity_type, entity_id) " +
                    "values ('${UUID.randomUUID()}', '${UUID.randomUUID()}', 'system', 'x', 'fixture', 'x')",
            )
            it.executeQuery("select count(*) from audit.audit_event").use { rs -> assertThat(rs.next()).isTrue() }
        }
    }

    @Test
    fun `runtime role cannot update, delete or truncate`() = asRuntimeRole { c ->
        CHANGES.forEach { sql ->
            val e = assertThrows<SQLException>(sql) { c.createStatement().use { it.execute(sql) } }
            assertThat(e.sqlState).describedAs(sql).isEqualTo("42501") // insufficient_privilege
            assertThat(e.message).describedAs(sql).contains("permission denied")
        }
    }

    @Test
    fun `owner and superuser are blocked by the trigger`() {
        CHANGES.forEach { sql ->
            val e = assertThrows<DataAccessException>(sql) { jdbc.execute(sql) }
            assertThat(e.mostSpecificCause.message).describedAs(sql).contains("append-only")
        }
        assertThat(jdbc.queryForObject("select count(*) from audit.audit_event where action = 'tampered'", Long::class.java)).isZero()
    }

    /** Default privileges give alinha_app UPDATE/DELETE on new tables; none may land in schema audit. */
    @Test
    fun `runtime role cannot update or delete any audit table`() {
        val writable = jdbc.queryForList(
            """
            select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
            where n.nspname = 'audit' and c.relkind in ('r', 'p')
              and (has_table_privilege('alinha_app', c.oid, 'UPDATE')
                   or has_table_privilege('alinha_app', c.oid, 'DELETE')
                   or has_table_privilege('alinha_app', c.oid, 'TRUNCATE'))
            """.trimIndent(),
            String::class.java,
        )
        assertThat(writable).isEmpty()
    }
}
