package pt.alinha.core.jobs.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import pt.alinha.core.jobs.JobDispatcher
import pt.alinha.core.jobs.internal.RedisJobStreams.Companion.REASON
import pt.alinha.core.jobs.internal.RedisJobStreams.Companion.RESULT
import pt.alinha.core.jobs.internal.RedisJobStreams.Companion.RESULTS_DLQ
import pt.alinha.core.jobs.internal.RedisJobStreams.Companion.RESULTS_GROUP
import pt.alinha.core.jobs.internal.RedisJobStreams.Companion.RESULTS_STREAM
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.TenantId
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** claim-min-idle 0: every unacknowledged entry is stale at once, so no test waits. */
@SpringBootTest(
    classes = [JobsTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["alinha.jobs.results.claim-min-idle=0ms", "alinha.jobs.results.max-deliveries=3"],
)
class JobResultRecoveryTest {
    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) = JobsInfrastructure.register(registry)

        private const val CRASHED = "crashed-instance"
    }

    @Autowired private lateinit var dispatcher: JobDispatcher
    @Autowired private lateinit var relay: OutboxRelay
    @Autowired private lateinit var consumer: JobResultConsumer
    @Autowired private lateinit var streams: RedisJobStreams
    @Autowired private lateinit var tenants: TenantContext
    @Autowired private lateinit var tx: TransactionTemplate
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var json: JsonMapper

    private val tenant = TenantId(UUID.randomUUID())
    private val ops get() = redis.opsForStream<String, String>()

    @BeforeEach
    fun clean() {
        jdbc.update("delete from core.job_outbox")
        redis.delete(listOf(RESULTS_STREAM, RESULTS_DLQ, COLLECT_STREAM))
        streams.ensureResultsGroup()
    }

    private fun published() = dispatcher.dispatchAs(tenants, tx, tenant, collectRequest()).also { relay.relayOnce() }

    private fun sendCompleted(id: UUID): RecordId = checkNotNull(
        ops.add(
            RESULTS_STREAM,
            mapOf(
                RESULT to json.writeValueAsString(
                    mapOf(
                        "jobId" to id.toString(), "type" to "evidence.collect", "version" to 1,
                        "tenantId" to tenant.value.toString(), "attempt" to 1, "status" to "completed",
                        "result" to emptyMap<String, Any>(), "finishedAt" to Instant.now().toString(),
                    ),
                ),
            ),
        ),
    )

    /** Another instance reads the entry and dies before acknowledging it. */
    private fun readByCrashedInstance() =
        ops.read(Consumer.from(RESULTS_GROUP, CRASHED), StreamOffset.create(RESULTS_STREAM, ReadOffset.lastConsumed()))

    private fun status(id: UUID) =
        jdbc.queryForObject("select status from core.job_outbox where id = ?", String::class.java, id)

    private fun unacknowledged() = ops.pending(RESULTS_STREAM, RESULTS_GROUP).totalPendingMessages

    @Test
    fun `an entry a crashed instance never acknowledged is claimed and applied`() {
        val id = published()
        sendCompleted(id)
        assertThat(readByCrashedInstance()).hasSize(1)

        assertThat(consumer.consumeOnce()).isEqualTo(1)

        assertThat(status(id)).isEqualTo("completed")
        assertThat(unacknowledged()).isZero()
    }

    @Test
    fun `an entry delivered max-deliveries times is dead-lettered instead`() {
        val id = published()
        val entry = sendCompleted(id)
        readByCrashedInstance() // delivery 1
        ops.claim(RESULTS_STREAM, RESULTS_GROUP, CRASHED, Duration.ZERO, entry) // delivery 2
        ops.claim(RESULTS_STREAM, RESULTS_GROUP, CRASHED, Duration.ZERO, entry) // delivery 3 = max-deliveries

        assertThat(consumer.consumeOnce()).isEqualTo(1)

        assertThat(status(id)).isEqualTo("published")
        val dead = ops.range(RESULTS_DLQ, Range.unbounded()).orEmpty().single()
        assertThat(dead.value[REASON]).isEqualTo("not applied after 3 deliveries")
        assertThat(dead.value[RedisJobStreams.SOURCE_ID]).isEqualTo(entry.value)
        assertThat(unacknowledged()).isZero()
    }
}
