package pt.alinha.core.tenant.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The consultants group, in memory; can be told to fail. */
class FakeConsultantIdentity : ConsultantIdentity {
    val pool: MutableMap<String, MutableSet<TenantId>> = Collections.synchronizedMap(linkedMapOf())
    val assigned: MutableList<Pair<String, TenantId>> = Collections.synchronizedList(mutableListOf())
    @Volatile var failOnAssign = false

    /** Time [assign] takes, to make concurrent assignments overlap. */
    @Volatile var assignDelayMillis = 0L

    /** Runs right after [consultants] took its snapshot: what changes between the listing and the lock. */
    @Volatile var afterListing: () -> Unit = {}

    override fun consultants(): List<Consultant> {
        val listed = synchronized(pool) { pool.map { (subject, tenants) -> Consultant(subject, tenants.toSet()) } }
        afterListing()
        return listed
    }

    override fun assign(subject: String, tenantId: TenantId) {
        if (failOnAssign) error("identity provider down")
        Thread.sleep(assignDelayMillis)
        synchronized(pool) {
            require(subject in pool) { "Not an enabled consultant" }
            assigned += subject to tenantId
            pool.getValue(subject) += tenantId
        }
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
        identity.assignDelayMillis = 0
        identity.afterListing = {}
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

    /** A row of `core.consultant_assignment` at [at], an SQL expression. */
    private fun recordAt(tenant: TenantId, consultant: String, at: String) = jdbc.update(
        "insert into core.consultant_assignment (tenant_id, consultant_subject, assigned_at) values (?, ?, $at)",
        tenant.value, consultant,
    )

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
        consultants(marta to 0, joao to 0, ines to 1)
        // Marta and João were each given one organization by this flow (Marta's first); Inês's came from an admin.
        val first = newTenant()
        identity.pool.getValue(marta) += first
        recordAt(first, marta, "now() - interval '2 days'")
        val second = newTenant()
        identity.pool.getValue(joao) += second
        recordAt(second, joao, "now() - interval '1 day'")

        // All three have one organization each: Inês was never assigned one here, so she goes first.
        assertThat(assign(newTenant())?.subject).isEqualTo(ines)
        // Inês now has two; Marta and João have one each, and Marta's last assignment is the older one.
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
    @ExtendWith(OutputCaptureExtension::class)
    fun `a recorded consultant who left the group keeps the organization, it is not reassigned automatically`(output: CapturedOutput) {
        consultants(marta to 0, joao to 0)
        val tenant = newTenant()
        assertThat(assign(tenant)?.subject).isEqualTo(joao)
        identity.pool.remove(joao) // removed from the consultants group by an admin

        val again = assign(tenant)

        assertThat(again).isEqualTo(AssignedConsultant(joao, newlyAssigned = false))
        assertThat(identity.assigned).hasSize(1)
        assertThat(recorded(tenant)).isEqualTo(joao)
        assertThat(output).contains("no longer has it as an enabled consultant")
    }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `an organization a concurrent call assigned since the listing is returned without a warning`(output: CapturedOutput) {
        consultants(marta to 0)
        val tenant = newTenant()
        // The other call wrote to the identity provider and recorded the row after this call's listing.
        identity.afterListing = { recordAt(tenant, marta, "clock_timestamp()") }

        val result = assign(tenant)

        assertThat(result).isEqualTo(AssignedConsultant(marta, newlyAssigned = false))
        assertThat(identity.assigned).isEmpty()
        assertThat(output).doesNotContain("no longer has it as an enabled consultant")
    }

    @Test
    fun `the caller's lock_timeout is back once the lock is taken`() {
        consultants(marta to 0)
        val tenant = newTenant()

        val after = tenants.runAs(tenant) {
            tx.execute {
                jdbc.execute("set local lock_timeout = '3s'")
                assignment.assignConsultant()
                jdbc.queryForObject("select current_setting('lock_timeout')", String::class.java)
            }
        }

        assertThat(after).isEqualTo("3s")
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
    fun `a consultant who left the group since the listing is skipped, and the next one gets the organization`() {
        consultants(marta to 0, joao to 1)
        identity.afterListing = { identity.pool.remove(marta) } // disabled or removed from the group meanwhile
        val tenant = newTenant()

        val result = assign(tenant)

        assertThat(result).isEqualTo(AssignedConsultant(joao, newlyAssigned = true))
        assertThat(identity.assigned).containsExactly(joao to tenant)
        assertThat(recorded(tenant)).isEqualTo(joao)
        assertThat(auditDetails(tenant)).singleElement().asString().contains(joao)
    }

    @Test
    fun `when every listed consultant left the group, nothing is assigned`() {
        consultants(marta to 0)
        identity.afterListing = { identity.pool.clear() }
        val tenant = newTenant()

        assertThat(assign(tenant)).isNull()
        assertThat(recorded(tenant)).isNull()
        assertThat(auditDetails(tenant)).isEmpty()
    }

    @Test
    fun `concurrent assignments are serialised, so they do not both pick the least busy consultant`() {
        consultants(marta to 0, joao to 0)
        identity.assignDelayMillis = 300 // both list the pool before either has written to the identity provider
        val organizations = listOf(newTenant(), newTenant())
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = organizations.map { tenant -> pool.submit<AssignedConsultant?> { start.await(); assign(tenant) } }
            start.countDown()

            assertThat(results.map { it.get(10, TimeUnit.SECONDS)?.subject }).containsExactlyInAnyOrder(marta, joao)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `an assignment recorded while the identity provider was listed still counts`() {
        // The pool comes from a listing taken before the lock: Marta's newest organization is only in the database.
        consultants(marta to 0, joao to 0)
        val meanwhile = newTenant()
        identity.afterListing = { recordAt(meanwhile, marta, "clock_timestamp()") }

        assertThat(assign(newTenant())?.subject).isEqualTo(joao)
    }

    @Test
    fun `an organization an admin moved in the identity provider counts for its new consultant, not the recorded one`() {
        consultants(marta to 0, joao to 0)
        val moved = newTenant()
        recordAt(moved, marta, "now() - interval '1 day'") // assigned to Marta here...
        identity.pool.getValue(joao) += moved // ...and moved to João in the identity provider since

        assertThat(assign(newTenant())?.subject).isEqualTo(marta)
    }

    @Test
    fun `refuses to run outside a transaction`() {
        consultants(marta to 0)
        val tenant = newTenant()

        assertThrows<IllegalTransactionStateException> { tenants.runAs(tenant) { assignment.assignConsultant() } }
    }
}
