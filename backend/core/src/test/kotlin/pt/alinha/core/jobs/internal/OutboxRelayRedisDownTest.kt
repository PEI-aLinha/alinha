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
        "select status, publish_failures, next_attempt_at > now() as delayed, next_attempt_at, last_error " +
            "from core.job_outbox where id = ?",
        id,
    )

    private fun makeDue(id: UUID) = jdbc.update("update core.job_outbox set next_attempt_at = now() where id = ?", id)

    @Test
    fun `a Redis outage longer than max-publish-failures never kills the job`() {
        val id = dispatcher.dispatchAs(tenants, tx, tenant, collectRequest())

        repeat(5) { // max-publish-failures is 2
            makeDue(id)
            assertThat(relay.relayOnce()).isZero()
            row(id).let {
                assertThat(it["status"]).isEqualTo("pending")
                assertThat(it["publish_failures"]).isEqualTo(0)
                assertThat(it["delayed"]).isEqualTo(true)
                assertThat(it["last_error"] as String).isNotBlank()
            }
        }

        assertThat(notifier.notifyOnce()).isZero()
        assertThat(events.stream(JobFailed::class.java)).isEmpty()
    }

    @Test
    fun `a deferred job is not tried again before its delay`() {
        val id = dispatcher.dispatchAs(tenants, tx, tenant, collectRequest())
        relay.relayOnce()
        val retryAt = row(id)["next_attempt_at"]

        relay.relayOnce()

        assertThat(row(id)["next_attempt_at"]).isEqualTo(retryAt)
    }

    @Test
    fun `one failure stops the batch instead of waiting on every row`() {
        val ids = (1..5).map { dispatcher.dispatchAs(tenants, tx, tenant, collectRequest()) }
        relay.relayOnce()
        assertThat(ids.count { row(it)["last_error"] != null }).isEqualTo(1)
    }
}
