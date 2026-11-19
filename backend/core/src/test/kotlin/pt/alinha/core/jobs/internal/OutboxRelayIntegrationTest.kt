package pt.alinha.core.jobs.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Range
import org.springframework.data.redis.RedisSystemException
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
    @Autowired private lateinit var outbox: JobOutboxRepository
    @Autowired private lateinit var properties: JobsProperties

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

    @Test
    fun `each job is committed before the next one is published`() {
        val (first, second) = (1..2).map { dispatcher.dispatchAs(tenants, tx, tenant, collectRequest()) }
        var firstSeenAs: String? = null
        val streams = object : RedisJobStreams(redis) {
            override fun publish(job: OutboxJob) = super.publish(job).also {
                // Another connection, as JobResultConsumer would use: NOWAIT fails if the relay still held the lock.
                if (job.id == second) {
                    firstSeenAs = CompletableFuture.supplyAsync {
                        tx.execute {
                            jdbc.queryForObject(
                                "select status from core.job_outbox where id = ? for update nowait", String::class.java, first,
                            )
                        }
                    }.join()
                }
            }
        }

        assertThat(OutboxRelay(outbox, streams, tx, properties).relayOnce()).isEqualTo(2)
        assertThat(firstSeenAs).isEqualTo("published")
    }

    @Test
    fun `a job that fails with any error backs off without holding up the rest`() {
        val ids = (1..3).map { dispatcher.dispatchAs(tenants, tx, tenant, collectRequest()) }
        val poison = ids.first() // oldest, so it heads every batch
        val streams = object : RedisJobStreams(redis) {
            override fun publish(job: OutboxJob) =
                if (job.id == poison) throw IllegalStateException("not a DataAccessException") else super.publish(job)
        }
        val relay = OutboxRelay(outbox, streams, tx, properties)

        assertThat(relay.relayOnce()).isEqualTo(2) // the others go in the same round
        assertThat(relay.relayOnce()).isZero() // the poison row is backing off

        assertThat(publishedJobIds()).containsExactlyInAnyOrderElementsOf(ids - poison)
        assertThat(status(poison)).isEqualTo("pending")
        assertThat(jdbc.queryForObject("select publish_failures from core.job_outbox where id = ?", Int::class.java, poison))
            .isEqualTo(1)
    }

    @Test
    fun `a stream Redis refuses counts as a job failure, not an outage`() {
        val ids = (1..3).map { dispatcher.dispatchAs(tenants, tx, tenant, collectRequest()) }
        val refused = ids.first()
        val streams = object : RedisJobStreams(redis) {
            override fun publish(job: OutboxJob) =
                if (job.id == refused) {
                    throw RedisSystemException("WRONGTYPE Operation against a key holding the wrong kind of value", null)
                } else {
                    super.publish(job)
                }
        }

        assertThat(OutboxRelay(outbox, streams, tx, properties).relayOnce()).isEqualTo(2)

        assertThat(publishedJobIds()).containsExactlyInAnyOrderElementsOf(ids - refused)
        assertThat(jdbc.queryForObject("select publish_failures from core.job_outbox where id = ?", Int::class.java, refused))
            .isEqualTo(1)
    }
}
