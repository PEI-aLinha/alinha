package pt.alinha.core.jobs.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
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
import java.util.UUID

/** Jobs that fail to publish for a reason other than Redis, until they are dead. */
@SpringBootTest(
    classes = [JobsTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["alinha.jobs.relay.max-publish-failures=2"],
)
@RecordApplicationEvents
class DeadJobNotifierTest {
    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) = JobsInfrastructure.register(registry)
    }

    @Autowired private lateinit var dispatcher: JobDispatcher
    @Autowired private lateinit var notifier: DeadJobNotifier
    @Autowired private lateinit var outbox: JobOutboxRepository
    @Autowired private lateinit var properties: JobsProperties
    @Autowired private lateinit var tenants: TenantContext
    @Autowired private lateinit var tx: TransactionTemplate
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var events: ApplicationEvents

    private val tenant = TenantId(UUID.randomUUID())

    /** Fails every publish with an error that is not Redis'. */
    private val brokenRelay by lazy {
        val streams = object : RedisJobStreams(redis) {
            override fun publish(job: OutboxJob) = throw IllegalStateException("not a DataAccessException")
        }
        OutboxRelay(outbox, streams, tx, properties)
    }

    @BeforeEach
    fun clean() {
        jdbc.update("delete from core.job_outbox")
    }

    private fun row(id: UUID) =
        jdbc.queryForMap("select status, publish_failures, finished_at from core.job_outbox where id = ?", id)

    private fun makeDue(id: UUID) = jdbc.update("update core.job_outbox set next_attempt_at = now() where id = ?", id)

    private fun auditCount(id: UUID) = jdbc.queryForObject(
        "select count(*) from audit.audit_event where action = 'job.dead' and entity_id = ? and tenant_id = ?",
        Int::class.java,
        id.toString(),
        tenant.value,
    )

    @Test
    fun `a job that keeps failing for another reason dies and JobFailed is emitted once`() {
        val id = dispatcher.dispatchAs(tenants, tx, tenant, collectRequest())

        assertThat(brokenRelay.relayOnce()).isZero()
        assertThat(row(id)["status"]).isEqualTo("pending")
        assertThat(row(id)["publish_failures"]).isEqualTo(1)
        makeDue(id)
        brokenRelay.relayOnce()
        assertThat(row(id)["status"]).isEqualTo("dead")

        assertThat(notifier.notifyOnce()).isEqualTo(1)
        assertThat(notifier.notifyOnce()).isZero()

        val failed = events.stream(JobFailed::class.java).toList().single()
        assertThat(failed.jobId).isEqualTo(id)
        assertThat(failed.tenantId).isEqualTo(tenant)
        assertThat(failed.requestedBy).isEqualTo("code-repository")
        assertThat(failed.error.code).isEqualTo(JobError.PUBLISH_FAILED)
        assertThat(row(id)["finished_at"]).isNotNull()
        assertThat(auditCount(id)).isEqualTo(1)
    }
}
