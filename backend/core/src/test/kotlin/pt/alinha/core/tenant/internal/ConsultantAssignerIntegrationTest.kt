package pt.alinha.core.tenant.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import pt.alinha.core.audit.internal.AuditDatabase
import pt.alinha.core.audit.internal.AuditTestApplication
import pt.alinha.core.tenant.AssignedConsultant
import pt.alinha.core.tenant.ConsultantAssignment
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.TenantId
import java.util.Collections
import java.util.UUID

/** The consultants group, in memory; can be told to fail. */
class FakeConsultantIdentity : ConsultantIdentity {
    val pool: MutableMap<String, MutableSet<TenantId>> = Collections.synchronizedMap(linkedMapOf())
    val assigned: MutableList<Pair<String, TenantId>> = Collections.synchronizedList(mutableListOf())
    @Volatile var failOnAssign = false

    override fun consultants() = pool.map { (subject, tenants) -> Consultant(subject, tenants.toSet()) }

    override fun assign(subject: String, tenantId: TenantId) {
        if (failOnAssign) error("identity provider down")
        require(subject in pool) { "Not an enabled consultant" }
        assigned += subject to tenantId
        pool.getValue(subject) += tenantId
    }
}

@TestConfiguration
class ConsultantAssignmentTestConfig {
    @Bean fun consultantIdentity() = FakeConsultantIdentity()
}

@SpringBootTest(classes = [AuditTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ConsultantAssignmentTestConfig::class, ConsultantAssigner::class)
class ConsultantAssignerIntegrationTest {
    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) = AuditDatabase.register(registry)
    }

    @Autowired private lateinit var assignment: ConsultantAssignment
    @Autowired private lateinit var tenants: TenantContext
    @Autowired private lateinit var identity: FakeConsultantIdentity
    @Autowired private lateinit var tx: TransactionTemplate
    @Autowired private lateinit var jdbc: JdbcTemplate

    // Unique per test run: the database is shared with the other core test classes.
    private val marta = "marta-" + UUID.randomUUID()
    private val joao = "joao-" + UUID.randomUUID()
    private val ines = "ines-" + UUID.randomUUID()

    @BeforeEach
    fun reset() {
        identity.pool.clear()
        identity.assigned.clear()
        identity.failOnAssign = false
    }

    private fun newTenant(): TenantId {
        val tenant = TenantId(UUID.randomUUID())
        jdbc.update("insert into core.tenant (id, owner_subject) values (?, ?)", tenant.value, "test:" + tenant.value)
        return tenant
    }

    private fun assign(tenant: TenantId): AssignedConsultant? =
        tenants.runAs(tenant) { tx.execute { assignment.assignConsultant() } }

    private fun consultants(vararg entries: Pair<String, Int>) = entries.forEach { (subject, organizations) ->
        identity.pool[subject] = MutableList(organizations) { TenantId(UUID.randomUUID()) }.toMutableSet()
    }

    private fun recorded(tenant: TenantId): String? = jdbc.queryForList(
        "select consultant_subject from core.consultant_assignment where tenant_id = ?", String::class.java, tenant.value,
    ).singleOrNull()

    private fun auditDetails(tenant: TenantId): List<String> = jdbc.queryForList(
        "select details::text from audit.audit_event where tenant_id = ? and action = ?",
        String::class.java, tenant.value, CONSULTANT_ASSIGNED,
    )

    @Test
    fun `assigns the consultant with the fewest organizations, in the identity provider and the database`() {
        consultants(marta to 3, joao to 1, ines to 2)
        val tenant = newTenant()

        val result = assign(tenant)

        assertThat(result).isEqualTo(AssignedConsultant(joao, newlyAssigned = true))
        assertThat(identity.assigned).containsExactly(joao to tenant)
        assertThat(recorded(tenant)).isEqualTo(joao)
        assertThat(auditDetails(tenant)).singleElement().asString().contains(joao)
    }

    @Test
    fun `on a tie, a consultant never assigned goes first, then the one whose last assignment is oldest`() {
        consultants(marta to 0, joao to 0, ines to 0)
        // Marta and João were each given one organization before (oldest first); Inês never was.
        val first = newTenant()
        jdbc.update("insert into core.consultant_assignment (tenant_id, consultant_subject, assigned_at) values (?, ?, now() - interval '2 days')", first.value, marta)
        val second = newTenant()
        jdbc.update("insert into core.consultant_assignment (tenant_id, consultant_subject, assigned_at) values (?, ?, now() - interval '1 day')", second.value, joao)

        assertThat(assign(newTenant())?.subject).isEqualTo(ines)
        // Inês now has one organization; Marta and João have none, and Marta's last assignment is the older one.
        assertThat(assign(newTenant())?.subject).isEqualTo(marta)
    }

    @Test
    fun `assigning again returns the same consultant and changes nothing`() {
        consultants(marta to 0, joao to 1)
        val tenant = newTenant()
        assign(tenant)

        val again = assign(tenant)

        assertThat(again).isEqualTo(AssignedConsultant(marta, newlyAssigned = false))
        assertThat(identity.assigned).hasSize(1)
        assertThat(auditDetails(tenant)).hasSize(1)
    }

    @Test
    fun `an organization already in a consultant's tenants is recorded, not assigned again`() {
        val tenant = newTenant()
        consultants(marta to 0, joao to 5)
        identity.pool.getValue(joao) += tenant // e.g. set by an admin, or the demo realm

        val result = assign(tenant)

        assertThat(result).isEqualTo(AssignedConsultant(joao, newlyAssigned = false))
        assertThat(identity.assigned).isEmpty()
        assertThat(recorded(tenant)).isEqualTo(joao)
        assertThat(auditDetails(tenant)).singleElement().asString().contains("\"alreadyInIdentityProvider\": true")
    }

    @Test
    fun `with no consultant, nothing is assigned`() {
        val tenant = newTenant()

        assertThat(assign(tenant)).isNull()
        assertThat(recorded(tenant)).isNull()
        assertThat(auditDetails(tenant)).isEmpty()
    }

    @Test
    fun `a failure in the identity provider rolls the assignment back`() {
        consultants(marta to 0)
        identity.failOnAssign = true
        val tenant = newTenant()

        assertThrows<IllegalStateException> { assign(tenant) }

        assertThat(recorded(tenant)).isNull()
        assertThat(auditDetails(tenant)).isEmpty()
    }

    @Test
    fun `refuses to run outside a transaction`() {
        consultants(marta to 0)
        val tenant = newTenant()

        assertThrows<IllegalTransactionStateException> { tenants.runAs(tenant) { assignment.assignConsultant() } }
    }
}
