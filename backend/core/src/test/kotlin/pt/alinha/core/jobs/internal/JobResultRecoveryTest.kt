package pt.alinha.core.jobs.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.support.TransactionTemplate
import pt.alinha.core.jobs.JobDispatcher
import pt.alinha.core.jobs.JobFailed
import pt.alinha.core.jobs.JobError
import pt.alinha.core.jobs.internal.RedisJobStreams.Companion.REASON
import pt.alinha.core.jobs.internal.RedisJobStreams.Companion.RESULT
import pt.alinha.core.jobs.internal.RedisJobStreams.Companion.RESULTS_ATTEMPTS
import pt.alinha.core.jobs.internal.RedisJobStreams.Companion.RESULTS_DLQ
import pt.alinha.core.jobs.internal.RedisJobStreams.Companion.RESULTS_GROUP
import pt.alinha.core.jobs.internal.RedisJobStreams.Companion.RESULTS_STREAM
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.TenantId
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** claim-min-idle 0: every unacknowledged entry is stale at once, so no test waits. */
@RecordApplicationEvents
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
    @Autowired private lateinit var handler: JobResultHandler
    @Autowired private lateinit var jdbcClient: JdbcClient
    @Autowired private lateinit var properties: JobsProperties
    @Autowired private lateinit var events: ApplicationEvents

    private val tenant = TenantId(UUID.randomUUID())
    private val ops get() = redis.opsForStream<String, String>()

    @BeforeEach
    fun clean() {
        jdbc.update("delete from core.job_outbox")
        redis.delete(listOf(RESULTS_STREAM, RESULTS_DLQ, RESULTS_ATTEMPTS, COLLECT_STREAM))
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

    private fun deliveries(entry: RecordId) =
        ops.pending(RESULTS_STREAM, RESULTS_GROUP, Range.unbounded<String>(), 10).single { it.id == entry }.totalDeliveryCount

    private fun deadLetters() = ops.range(RESULTS_DLQ, Range.unbounded()).orEmpty()

    /**
     * A consumer whose database takes writes while [up] says so. The handler fails while it is down, and when it is
     * called with [failNext] set it takes the database down first, as an outage in the middle of a round would.
     * [sqlState] shapes the error the handler throws. With [flapping], the handler fails and the database is down
     * only for the probe right after that failure, so the next round starts again.
     */
    private inner class Outage(settings: JobsProperties = properties) {
        var up = true
        var failNext = false
        var sqlState: String? = null
        var flapping = false
        private var downForNextProbe = false
        private val outbox = object : JobOutboxRepository(jdbcClient) {
            override fun acceptsWrites(): Boolean {
                if (downForNextProbe) {
                    downForNextProbe = false
                    return false
                }
                return up
            }
        }
        private val flakyHandler = mock(JobResultHandler::class.java).also {
            `when`(it.handle(any())).thenAnswer { call ->
                if (failNext) up = false
                if (flapping) {
                    downForNextProbe = true
                    throw downError()
                }
                if (!up) throw downError()
                handler.handle(call.getArgument(0))
            }
            `when`(it.failUnapplied(any(), anyString())).thenAnswer { call ->
                handler.failUnapplied(call.getArgument(0), call.getArgument(1))
            }
        }

        private fun downError(): RuntimeException = sqlState?.let {
            DataAccessResourceFailureException("write refused", SQLException("write refused", it))
        } ?: CannotCreateTransactionException("database down")

        val consumer = JobResultConsumer(streams, flakyHandler, outbox, settings)
    }

    private fun failedEvent(id: UUID) = events.stream(JobFailed::class.java).toList().single { it.jobId == id }

    @Test
    fun `an entry a crashed instance never acknowledged is claimed and applied`() {
        val id = published()
        sendCompleted(id)
        assertThat(readByCrashedInstance()).hasSize(1)

        assertThat(consumer.consumeOnce()).isEqualTo(1)

        assertThat(status(id)).isEqualTo("completed")
        assertThat(unacknowledged()).isZero()
        assertThat(redis.opsForStream<String, String>().size(RESULTS_STREAM)).isZero() // deleted once applied
    }

    @Test
    fun `a database outage never uses up the deliveries of a result`() {
        val id = published()
        val entry = sendCompleted(id)
        readByCrashedInstance() // delivery 1
        val outage = Outage().apply { up = false }

        repeat(5) { assertThat(outage.consumer.consumeOnce()).isZero() } // max-deliveries is 3

        assertThat(deliveries(entry)).isEqualTo(1)
        assertThat(deadLetters()).isEmpty()
        outage.up = true
        assertThat(outage.consumer.consumeOnce()).isEqualTo(1)
        assertThat(status(id)).isEqualTo("completed")
    }

    @Test
    fun `the database going down mid-round stops the round before reading more`() {
        val stale = sendCompleted(published())
        readByCrashedInstance()
        sendCompleted(published())
        val outage = Outage().apply { failNext = true }

        repeat(5) { assertThat(outage.consumer.consumeOnce()).isZero() }

        assertThat(deliveries(stale)).isEqualTo(2) // claimed once, then the database went down
        assertThat(ops.pending(RESULTS_STREAM, RESULTS_GROUP, Range.unbounded<String>(), 10).map { it.id })
            .containsExactly(stale) // the fresh entry was never read
        assertThat(deadLetters()).isEmpty()
    }

    @Test
    fun `a read-only database stops the round before reading more`() {
        val stale = sendCompleted(published())
        readByCrashedInstance()
        sendCompleted(published())
        val outage = Outage().apply { up = false; sqlState = "25006" } // read-only transaction

        assertThat(outage.consumer.consumeOnce()).isZero()

        assertThat(ops.pending(RESULTS_STREAM, RESULTS_GROUP, Range.unbounded<String>(), 10).map { it.id })
            .containsExactly(stale) // the fresh entry was never read
    }

    @Test
    fun `a read-only database never uses up the attempts of a result`() {
        val id = published()
        sendCompleted(id)
        val outage = Outage().apply { up = false; sqlState = "25006" }

        repeat(5) { assertThat(outage.consumer.consumeOnce()).isZero() } // max-deliveries is 3

        assertThat(deadLetters()).isEmpty()
        outage.up = true
        assertThat(outage.consumer.consumeOnce()).isEqualTo(1)
        assertThat(status(id)).isEqualTo("completed")
    }

    @Test
    fun `entries a flapping database never let us try are not dead-lettered`() {
        val first = published()
        val second = published()
        sendCompleted(first)
        sendCompleted(second)
        readByCrashedInstance() // both pending; every round claims both but only tries the first
        val outage = Outage().apply { flapping = true; sqlState = "08006" }

        repeat(5) { assertThat(outage.consumer.consumeOnce()).isZero() } // max-deliveries is 3

        assertThat(deadLetters()).isEmpty()
        outage.flapping = false
        assertThat(outage.consumer.consumeOnce()).isEqualTo(2)
        assertThat(status(first)).isEqualTo("completed")
        assertThat(status(second)).isEqualTo("completed")
    }

    @Test
    fun `a result that keeps failing is dead-lettered after max-deliveries attempts`() {
        val id = published()
        val entry = sendCompleted(id)
        var tries = 0
        val buggy = mock(JobResultHandler::class.java).also {
            `when`(it.handle(any())).thenAnswer { tries++; throw IllegalStateException("bug") }
            `when`(it.failUnapplied(any(), anyString())).thenAnswer { call ->
                handler.failUnapplied(call.getArgument(0), call.getArgument(1))
            }
        }
        val consumer = JobResultConsumer(streams, buggy, JobOutboxRepository(jdbcClient), properties)

        repeat(3) { assertThat(consumer.consumeOnce()).isZero() } // max-deliveries is 3
        assertThat(consumer.consumeOnce()).isEqualTo(1)

        assertThat(tries).isEqualTo(3)
        assertThat(status(id)).isEqualTo("failed") // the plugin hears about it instead of waiting for #86
        val dead = deadLetters().single()
        assertThat(dead.value[REASON]).isEqualTo("not applied after 3 attempts")
        assertThat(dead.value[RedisJobStreams.SOURCE_ID]).isEqualTo(entry.value)
        assertThat(unacknowledged()).isZero()
        assertThat(ops.size(RESULTS_STREAM)).isZero()
        assertThat(redis.opsForHash<String, String>().hasKey(RESULTS_ATTEMPTS, entry.value)).isFalse()
        assertThat(failedEvent(id).error.code).isEqualTo(JobError.RESULT_NOT_APPLIED)
    }

    @Test
    fun `claims by other instances do not count as attempts`() {
        val id = published()
        val entry = sendCompleted(id)
        readByCrashedInstance()
        repeat(4) { ops.claim(RESULTS_STREAM, RESULTS_GROUP, CRASHED, Duration.ZERO, entry) } // 5 deliveries, 0 attempts

        assertThat(consumer.consumeOnce()).isEqualTo(1)

        assertThat(status(id)).isEqualTo("completed")
        assertThat(deadLetters()).isEmpty()
    }

    @Test
    fun `a poison entry that throws an outage SQLState while the database takes writes is dead-lettered`() {
        val id = published()
        sendCompleted(id)
        // The error looks like an outage, but the write probe says the database is fine: it is this entry.
        val alwaysOutageError = mock(JobResultHandler::class.java).also {
            `when`(it.handle(any())).thenThrow(
                DataAccessResourceFailureException("connection reset", SQLException("connection reset", "08006")),
            )
            `when`(it.failUnapplied(any(), anyString())).thenAnswer { call ->
                handler.failUnapplied(call.getArgument(0), call.getArgument(1))
            }
        }
        val consumer = JobResultConsumer(streams, alwaysOutageError, JobOutboxRepository(jdbcClient), properties)

        repeat(3) { assertThat(consumer.consumeOnce()).isZero() } // max-deliveries is 3
        assertThat(consumer.consumeOnce()).isEqualTo(1)

        assertThat(deadLetters().single().value[REASON]).isEqualTo("not applied after 3 attempts")
        assertThat(status(id)).isEqualTo("failed")
        assertThat(failedEvent(id).error.code).isEqualTo(JobError.RESULT_NOT_APPLIED)
    }

    @Test
    fun `an entry that only ever meets a flapping database is dead-lettered at max-total-deliveries`() {
        val id = published()
        val entry = sendCompleted(id)
        val capped = properties.copy(results = properties.results.copy(maxTotalDeliveries = 4))
        val outage = Outage(capped).apply { flapping = true }

        repeat(4) { assertThat(outage.consumer.consumeOnce()).isZero() } // every attempt given back
        assertThat(deliveries(entry)).isEqualTo(4)
        assertThat(outage.consumer.consumeOnce()).isEqualTo(1)

        assertThat(deadLetters().single().value[REASON]).isEqualTo("delivered 4 times without being applied")
        assertThat(status(id)).isEqualTo("failed")
        assertThat(failedEvent(id).error.code).isEqualTo(JobError.RESULT_NOT_APPLIED)
    }

    @Test
    fun `the write probe fails on a read-only connection`() {
        val connection = checkNotNull(jdbc.dataSource).connection
        try {
            connection.createStatement().use { it.execute("set default_transaction_read_only = on") }
            val readOnly = JdbcClient.create(SingleConnectionDataSource(connection, true))
            assertThat(JobOutboxRepository(readOnly).acceptsWrites()).isFalse()
            assertThat(JobOutboxRepository(jdbcClient).acceptsWrites()).isTrue()
        } finally {
            connection.createStatement().use { it.execute("reset default_transaction_read_only") }
            connection.close()
        }
    }
}
