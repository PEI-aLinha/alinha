package pt.alinha.core.jobs.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.transaction.support.TransactionTemplate
import pt.alinha.core.jobs.JobDispatcher
import pt.alinha.core.jobs.JobError
import pt.alinha.core.jobs.JobFailed
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.TenantId
import java.net.ServerSocket
import java.util.UUID

/** Redis points at a port with nothing listening. */
@SpringBootTest(
    classes = [JobsTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["alinha.jobs.relay.max-publish-failures=2"],
)
@RecordApplicationEvents
class OutboxRelayRedisDownTest {
    companion object {
        private val closedPort = ServerSocket(0).use { it.localPort }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) = JobsInfrastructure.register(registry, redisPort = closedPort)
    }

    @Autowired private lateinit var dispatcher: JobDispatcher
    @Autowired private lateinit var relay: OutboxRelay
    @Autowired private lateinit var notifier: DeadJobNotifier
    @Autowired private lateinit var tenants: TenantContext
    @Autowired private lateinit var tx: TransactionTemplate
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var events: ApplicationEvents

    private val tenant = TenantId(UUID.randomUUID())

    @BeforeEach
    fun clean() {
        jdbc.update("delete from core.job_outbox")
    }

    private fun row(id: UUID) = jdbc.queryForMap(
        "select status, publish_failures, next_attempt_at > now() as delayed, last_error, finished_at " +
            "from core.job_outbox where id = ?",
        id,
    )

    private fun makeDue(id: UUID) = jdbc.update("update core.job_outbox set next_attempt_at = now() where id = ?", id)

    @Test
    fun `failed publish backs off, then the job dies and JobFailed is emitted once`() {
        val id = dispatcher.dispatchAs(tenants, tx, tenant, collectRequest())

        assertThat(relay.relayOnce()).isZero()
        row(id).let {
            assertThat(it["status"]).isEqualTo("pending")
            assertThat(it["publish_failures"]).isEqualTo(1)
            assertThat(it["delayed"]).isEqualTo(true)
            assertThat(it["last_error"] as String).isNotBlank()
        }
        assertThat(relay.relayOnce()).isZero() // still backing off: not even tried
        assertThat(row(id)["publish_failures"]).isEqualTo(1)

        makeDue(id)
        relay.relayOnce()
        assertThat(row(id)["status"]).isEqualTo("dead")

        assertThat(notifier.notifyOnce()).isEqualTo(1)
        assertThat(notifier.notifyOnce()).isZero()

        val failed = events.stream(JobFailed::class.java).toList().single()
        assertThat(failed.jobId).isEqualTo(id)
        assertThat(failed.tenantId).isEqualTo(tenant)
        assertThat(failed.requestedBy).isEqualTo("code-repository")
        assertThat(failed.error.code).isEqualTo(JobError.PUBLISH_FAILED)
        assertThat(row(id)["finished_at"]).isNotNull()
        assertThat(
            jdbc.queryForObject(
                "select count(*) from audit.audit_event where action = 'job.dead' and entity_id = ? and tenant_id = ?",
                Int::class.java,
                id.toString(),
                tenant.value,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `one failure stops the batch instead of waiting on every row`() {
        val ids = (1..5).map { dispatcher.dispatchAs(tenants, tx, tenant, collectRequest()) }
        relay.relayOnce()
        assertThat(ids.count { row(it)["publish_failures"] == 1 }).isEqualTo(1)
    }
}
