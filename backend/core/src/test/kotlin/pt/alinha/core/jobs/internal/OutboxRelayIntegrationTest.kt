package pt.alinha.core.jobs.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import pt.alinha.core.jobs.JobDispatcher
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.TenantId
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.CompletableFuture

@SpringBootTest(classes = [JobsTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboxRelayIntegrationTest {
    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) = JobsInfrastructure.register(registry)
    }

    @Autowired private lateinit var dispatcher: JobDispatcher
    @Autowired private lateinit var relay: OutboxRelay
    @Autowired private lateinit var tenants: TenantContext
    @Autowired private lateinit var tx: TransactionTemplate
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var json: JsonMapper

    private val tenant = TenantId(UUID.randomUUID())

    @BeforeEach
    fun clean() {
        jdbc.update("delete from core.job_outbox")
        redis.delete(COLLECT_STREAM)
    }

    private fun entries() = redis.opsForStream<String, String>().range(COLLECT_STREAM, Range.unbounded()).orEmpty()

    private fun publishedJobIds() = entries().map {
        UUID.fromString(json.readTree(it.value.getValue(RedisJobStreams.ENVELOPE)).get("jobId").asString())
    }

    private fun status(id: UUID) =
        jdbc.queryForObject("select status from core.job_outbox where id = ?", String::class.java, id)

    @Test
    fun `a crash between commit and publish does not lose the job`() {
        // Commit, then "crash": nothing published (relay scheduling is off in tests).
        val id = dispatcher.dispatchAs(tenants, tx, tenant, collectRequest())
        assertThat(entries()).isEmpty()
        assertThat(status(id)).isEqualTo("pending")

        // "Restart": a relay run finds the committed row and publishes it.
        assertThat(relay.relayOnce()).isEqualTo(1)

        val entry = entries().single()
        val envelope = json.readTree(entry.value.getValue(RedisJobStreams.ENVELOPE))
        assertThat(envelope.get("jobId").asString()).isEqualTo(id.toString())
        assertThat(envelope.get("tenantId").asString()).isEqualTo(tenant.value.toString())
        assertThat(entry.value[RedisJobStreams.ATTEMPT]).isEqualTo("1")
        assertThat(status(id)).isEqualTo("published")
        assertThat(relay.relayOnce()).isZero()
    }

    @Test
    fun `a crash after XADD but before the update republishes the same job`() {
        val id = dispatcher.dispatchAs(tenants, tx, tenant, collectRequest())
        relay.relayOnce()
        // Same state as if the relay died after XADD: the row update never committed.
        jdbc.update("update core.job_outbox set status = 'pending', published_at = null where id = ?", id)

        relay.relayOnce()

        assertThat(publishedJobIds()).containsExactly(id, id) // at-least-once; workers dedupe by (jobId, attempt)
    }

    @Test
    fun `a rolled-back dispatch is never published`() {
        tenants.runAs(tenant) {
            tx.executeWithoutResult { status ->
                dispatcher.dispatch(collectRequest())
                status.setRollbackOnly()
            }
        }
        assertThat(relay.relayOnce()).isZero()
        assertThat(entries()).isEmpty()
    }

    @Test
    fun `two relays in parallel publish each job exactly once`() {
        val ids = (1..40).map { dispatcher.dispatchAs(tenants, tx, tenant, collectRequest()) }

        repeat(3) {
            val runs = List(2) { CompletableFuture.supplyAsync { relay.relayOnce() } }
            runs.forEach { it.join() }
        }

        assertThat(publishedJobIds()).containsExactlyInAnyOrderElementsOf(ids)
    }
}
