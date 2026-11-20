package pt.alinha.core.jobs.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import pt.alinha.core.jobs.JobDispatcher
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.TenantId
import java.util.UUID

// No mock web environment: runAs refuses to run inside a (fake) request.
@SpringBootTest(classes = [JobsTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JobDispatcherIntegrationTest {
    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) = JobsInfrastructure.register(registry)
    }

    @Autowired private lateinit var dispatcher: JobDispatcher
    @Autowired private lateinit var tenants: TenantContext
    @Autowired private lateinit var tx: TransactionTemplate
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val tenantA = TenantId(UUID.randomUUID())
    private val tenantB = TenantId(UUID.randomUUID())

    @BeforeEach
    fun clean() {
        jdbc.update("delete from core.job_outbox")
        // Stands in for the plugin's own write in the same transaction.
        jdbc.execute("create table if not exists core.job_outbox_probe (id int primary key)")
        jdbc.update("delete from core.job_outbox_probe")
    }

    private fun outboxCount(id: UUID) =
        jdbc.queryForObject("select count(*) from core.job_outbox where id = ?", Int::class.java, id)

    private fun auditCount(id: UUID) = jdbc.queryForObject(
        "select count(*) from audit.audit_event where entity_type = 'job' and entity_id = ? and action = 'job.dispatched'",
        Int::class.java,
        id.toString(),
    )

    @Test
    fun `refuses to dispatch outside a transaction`() {
        assertThrows<IllegalTransactionStateException> {
            tenants.runAs(tenantA) { dispatcher.dispatch(collectRequest()) }
        }
    }

    @Test
    fun `writes a pending job with the stamped envelope and an audit entry`() {
        val id = dispatcher.dispatchAs(tenants, tx, tenantA, collectRequest())

        val row = jdbc.queryForMap(
            "select tenant_id, status, attempt, requested_by, envelope->>'jobId' as job_id, envelope->>'tenantId' as env_tenant " +
                "from core.job_outbox where id = ?",
            id,
        )
        assertThat(row["tenant_id"]).isEqualTo(tenantA.value)
        assertThat(row["status"]).isEqualTo("pending")
        assertThat(row["attempt"]).isEqualTo(1)
        assertThat(row["requested_by"]).isEqualTo("code-repository")
        assertThat(row["job_id"]).isEqualTo(id.toString())
        assertThat(row["env_tenant"]).isEqualTo(tenantA.value.toString())
        assertThat(auditCount(id)).isEqualTo(1)
    }

    @Test
    fun `caller rollback leaves no job and no audit entry`() {
        var id: UUID? = null
        tenants.runAs(tenantA) {
            tx.executeWithoutResult { status ->
                id = dispatcher.dispatch(collectRequest())
                status.setRollbackOnly()
            }
        }
        val rolledBack = checkNotNull(id)
        assertThat(outboxCount(rolledBack)).isZero()
        assertThat(auditCount(rolledBack)).isZero()
    }

    @Test
    fun `invalid payload throws and writes nothing`() {
        assertThrows<IllegalArgumentException> {
            dispatcher.dispatchAs(tenants, tx, tenantA, collectRequest(payload = mapOf("provider" to "code-repository")))
        }
        assertThat(jdbc.queryForObject("select count(*) from core.job_outbox", Int::class.java)).isZero()
    }

    @Test
    fun `an invalid payload the caller catches does not roll back the caller's transaction`() {
        tenants.runAs(tenantA) {
            tx.executeWithoutResult {
                jdbc.update("insert into core.job_outbox_probe (id) values (1)")
                assertThrows<IllegalArgumentException> {
                    dispatcher.dispatch(collectRequest(payload = mapOf("provider" to "code-repository")))
                }
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from core.job_outbox_probe", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `same idempotency key returns the same job, per tenant`() {
        val first = dispatcher.dispatchAs(tenants, tx, tenantA, collectRequest(key = "collect:1"))
        val again = dispatcher.dispatchAs(tenants, tx, tenantA, collectRequest(key = "collect:1"))
        val otherTenant = dispatcher.dispatchAs(tenants, tx, tenantB, collectRequest(key = "collect:1"))

        assertThat(again).isEqualTo(first)
        assertThat(otherTenant).isNotEqualTo(first)
        assertThat(jdbc.queryForObject("select count(*) from core.job_outbox", Int::class.java)).isEqualTo(2)
        assertThat(auditCount(first)).isEqualTo(1)
    }

    @Test
    fun `another plugin reusing a key gets its own job`() {
        val mine = dispatcher.dispatchAs(tenants, tx, tenantA, collectRequest(key = "collect:1"))
        val theirs = dispatcher.dispatchAs(tenants, tx, tenantA, collectRequest(key = "collect:1", requestedBy = "assessment"))

        assertThat(theirs).isNotEqualTo(mine)
        assertThat(auditCount(theirs)).isEqualTo(1)
    }

    @Test
    fun `jobs without a key never clash`() {
        val a = dispatcher.dispatchAs(tenants, tx, tenantA, collectRequest())
        val b = dispatcher.dispatchAs(tenants, tx, tenantA, collectRequest())
        assertThat(a).isNotEqualTo(b)
    }
}
