package pt.alinha.core.audit.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import pt.alinha.core.audit.AiProvenance
import pt.alinha.core.audit.AuditFilter
import pt.alinha.core.audit.AuditHistory
import pt.alinha.core.audit.AuditTrail
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.TenantId
import java.time.Instant
import java.util.UUID

@SpringBootTest(classes = [AuditTestApplication::class])
class AuditTrailIntegrationTest {
    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) = AuditDatabase.register(registry)
    }

    @Autowired private lateinit var audit: AuditTrail
    @Autowired private lateinit var history: AuditHistory
    @Autowired private lateinit var tenants: TenantContext
    @Autowired private lateinit var tx: TransactionTemplate
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val tenantA = TenantId(UUID.randomUUID())
    private val tenantB = TenantId(UUID.randomUUID())
    private val page = PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "occurredAt"))

    @BeforeEach
    fun businessTable() {
        jdbc.execute("create table if not exists public.business_fixture (id uuid primary key)")
    }

    private fun recordAs(tenant: TenantId, entityId: String, provenance: AiProvenance? = null) =
        tenants.runAs(tenant) {
            tx.executeWithoutResult {
                audit.record("fixture.create", "fixture", entityId, mapOf("field" to "name"), provenance)
            }
        }

    private fun auditRows(entityId: String): Long =
        jdbc.queryForObject("select count(*) from audit.audit_event where entity_id = ?", Long::class.java, entityId)!!

    @Test
    fun `records with the current tenant and the system actor`() {
        val entityId = UUID.randomUUID().toString()

        recordAs(tenantA, entityId)

        val row = jdbc.queryForMap("select tenant_id, actor, action, details::text as details from audit.audit_event where entity_id = ?", entityId)
        assertThat(row["tenant_id"]).isEqualTo(tenantA.value)
        assertThat(row["actor"]).isEqualTo("system")
        assertThat(row["action"]).isEqualTo("fixture.create")
        assertThat(row["details"] as String).contains("\"field\"").contains("\"name\"")
    }

    @Test
    fun `rollback of the business write also rolls back the audit entry`() {
        val id = UUID.randomUUID()

        assertThrows<IllegalStateException> {
            tenants.runAs(tenantA) {
                tx.executeWithoutResult {
                    jdbc.update("insert into public.business_fixture (id) values (?)", id)
                    audit.record("fixture.create", "fixture", id.toString())
                    error("business rule failed")
                }
            }
        }

        assertThat(jdbc.queryForObject("select count(*) from public.business_fixture where id = ?", Long::class.java, id)).isZero()
        assertThat(auditRows(id.toString())).isZero()
    }

    @Test
    fun `record writes the row before it returns, inside the open transaction`() {
        val entityId = UUID.randomUUID().toString()

        val rowsBeforeCommit = tenants.runAs(tenantA) {
            tx.execute {
                audit.record("fixture.create", "fixture", entityId)
                auditRows(entityId) // same connection: sees the flushed, uncommitted INSERT
            }
        }

        assertThat(rowsBeforeCommit).isEqualTo(1L)
    }

    @Test
    fun `record outside a transaction is refused`() {
        val entityId = UUID.randomUUID().toString()

        assertThrows<IllegalTransactionStateException> {
            tenants.runAs(tenantA) { audit.record("fixture.create", "fixture", entityId) }
        }
        assertThat(auditRows(entityId)).isZero()
    }

    @Test
    fun `AI provenance round-trips through the history`() {
        val entityId = UUID.randomUUID().toString()
        val provenance = AiProvenance(
            model = "model-x",
            promptVersion = "roadmap.v1",
            sources = listOf("nis2-qnrcs/v1#GV.1", "evidence/42"),
            generatedAt = Instant.parse("2026-11-16T10:00:00Z"),
        )

        recordAs(tenantA, entityId, provenance)

        val entry = tenants.runAs(tenantA) { history.find(AuditFilter(entityId = entityId), page) }.content.single()
        assertThat(entry.provenance).isEqualTo(provenance)
    }

    @Test
    fun `history only shows the current tenant's entries`() {
        val entityId = UUID.randomUUID().toString()
        recordAs(tenantA, entityId)

        val seenByA = tenants.runAs(tenantA) { history.find(AuditFilter(entityId = entityId), page) }
        val seenByB = tenants.runAs(tenantB) { history.find(AuditFilter(entityId = entityId), page) }

        assertThat(seenByA.content).hasSize(1)
        assertThat(seenByB.content).isEmpty()
    }

    @Test
    fun `history filters by entity and time window, newest first`() {
        val tenant = TenantId(UUID.randomUUID())
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        recordAs(tenant, first)
        val between = Instant.now()
        recordAs(tenant, second)

        val all = tenants.runAs(tenant) { history.find(AuditFilter(entityType = "fixture"), page) }
        val after = tenants.runAs(tenant) { history.find(AuditFilter(from = between), page) }

        assertThat(all.content.map { it.entityId }).containsExactly(second, first)
        assertThat(after.content.map { it.entityId }).containsExactly(second)
    }
}
