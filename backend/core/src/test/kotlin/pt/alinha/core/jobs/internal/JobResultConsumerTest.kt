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
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.transaction.support.TransactionTemplate
import pt.alinha.core.jobs.JobCompleted
import pt.alinha.core.jobs.JobDispatcher
import pt.alinha.core.jobs.JobError
import pt.alinha.core.jobs.JobFailed
import pt.alinha.core.jobs.internal.RedisJobStreams.Companion.REASON
import pt.alinha.core.jobs.internal.RedisJobStreams.Companion.RESULT
import pt.alinha.core.jobs.internal.RedisJobStreams.Companion.RESULTS_DLQ
import pt.alinha.core.jobs.internal.RedisJobStreams.Companion.RESULTS_GROUP
import pt.alinha.core.jobs.internal.RedisJobStreams.Companion.RESULTS_STREAM
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.TenantId
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

@SpringBootTest(classes = [JobsTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
@RecordApplicationEvents
class JobResultConsumerTest {
    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) = JobsInfrastructure.register(registry)
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
    @Autowired private lateinit var events: ApplicationEvents

    private val tenant = TenantId(UUID.randomUUID())

    @BeforeEach
    fun clean() {
        jdbc.update("delete from core.job_outbox")
        redis.delete(listOf(RESULTS_STREAM, RESULTS_DLQ, RedisJobStreams.RESULTS_ATTEMPTS, COLLECT_STREAM))
        streams.ensureResultsGroup()
    }

    // --- helpers ---

    private fun dispatched() = dispatcher.dispatchAs(tenants, tx, tenant, collectRequest())

    private fun published() = dispatched().also { relay.relayOnce() }

    private fun result(
        jobId: UUID,
        status: String,
        attempt: Int = 1,
        tenantId: UUID = tenant.value,
        retryable: Boolean? = null,
        error: Map<String, String>? = if (status == "failed") mapOf("code" to "auth", "message" to "token expired") else null,
    ) = json.writeValueAsString(
        buildMap {
            put("jobId", jobId.toString())
            put("type", "evidence.collect")
            put("version", 1)
            put("tenantId", tenantId.toString())
            put("attempt", attempt)
            put("status", status)
            retryable?.let { put("retryable", it) }
            error?.let { put("error", it) }
            if (status == "completed") put("result", mapOf("repositories" to 2))
            put("finishedAt", Instant.now().toString())
        },
    )

    private fun send(result: String) = send(mapOf(RESULT to result))

    private fun send(fields: Map<String, String>) = redis.opsForStream<String, String>().add(RESULTS_STREAM, fields)

    private fun row(id: UUID) = jdbc.queryForMap(
        "select status, attempt, publish_failures, last_error, result::text as result, finished_at, " +
            "next_attempt_at > now() as delayed " +
            "from core.job_outbox where id = ?",
        id,
    )

    private fun unacknowledged() =
        redis.opsForStream<String, String>().pending(RESULTS_STREAM, RESULTS_GROUP).totalPendingMessages

    private fun deadLetters() = redis.opsForStream<String, String>().range(RESULTS_DLQ, Range.unbounded()).orEmpty()

    private fun audited(action: String, id: UUID) = jdbc.queryForObject(
        "select count(*) from audit.audit_event where action = ? and entity_id = ? and tenant_id = ?",
        Int::class.java,
        action,
        id.toString(),
        tenant.value,
    )

    private fun makeDue(id: UUID) = jdbc.update("update core.job_outbox set next_attempt_at = now() where id = ?", id)

    // --- tests ---

    @Test
    fun `a completed result finishes the job, emits JobCompleted and is acknowledged`() {
        val id = published()
        send(result(id, "completed"))

        assertThat(consumer.consumeOnce()).isEqualTo(1)

        row(id).let {
            assertThat(it["status"]).isEqualTo("completed")
            assertThat(it["finished_at"]).isNotNull()
            assertThat(json.readTree(it["result"] as String).get("result").get("repositories").asInt()).isEqualTo(2)
        }
        val completed = events.stream(JobCompleted::class.java).toList().single()
        assertThat(completed.jobId).isEqualTo(id)
        assertThat(completed.tenantId).isEqualTo(tenant)
        assertThat(completed.requestedBy).isEqualTo("code-repository")
        assertThat(completed.attempt).isEqualTo(1)
        assertThat(audited("job.completed", id)).isEqualTo(1)
        assertThat(unacknowledged()).isZero()
        assertThat(redis.opsForStream<String, String>().size(RESULTS_STREAM)).isZero() // deleted once applied
        assertThat(deadLetters()).isEmpty()
    }

    @Test
    fun `a retryable failure is published again with the next attempt`() {
        val id = published()
        jdbc.update("update core.job_outbox set publish_failures = 4 where id = ?", id) // from the first attempt
        send(result(id, "failed", retryable = true))

        consumer.consumeOnce()

        row(id).let {
            assertThat(it["status"]).isEqualTo("pending")
            assertThat(it["attempt"]).isEqualTo(2)
            assertThat(it["publish_failures"]).isEqualTo(0)
            assertThat(it["delayed"]).isEqualTo(true)
            assertThat(it["last_error"]).isEqualTo("auth: token expired")
        }
        assertThat(events.stream(JobFailed::class.java)).isEmpty()
        assertThat(audited("job.retried", id)).isEqualTo(1)

        makeDue(id)
        assertThat(relay.relayOnce()).isEqualTo(1)
        val attempts = redis.opsForStream<String, String>().range(COLLECT_STREAM, Range.unbounded()).orEmpty()
            .map { it.value[RedisJobStreams.ATTEMPT] }
        assertThat(attempts).containsExactly("1", "2")
    }

    @Test
    fun `a retryable failure on the last attempt fails the job`() {
        val id = published()
        jdbc.update("update core.job_outbox set max_attempts = 1 where id = ?", id)
        send(result(id, "failed", retryable = true))

        consumer.consumeOnce()

        assertThat(row(id)["status"]).isEqualTo("failed")
        assertThat(events.stream(JobFailed::class.java).toList().single().attempt).isEqualTo(1)
    }

    @Test
    fun `a failure that is not retryable fails the job with the worker's error`() {
        val id = published()
        send(result(id, "failed"))

        consumer.consumeOnce()

        row(id).let {
            assertThat(it["status"]).isEqualTo("failed")
            assertThat(it["finished_at"]).isNotNull()
            assertThat(it["last_error"]).isEqualTo("auth: token expired")
        }
        val failed = events.stream(JobFailed::class.java).toList().single()
        assertThat(failed.jobId).isEqualTo(id)
        assertThat(failed.error).isEqualTo(JobError("auth", "token expired"))
        assertThat(audited("job.failed", id)).isEqualTo(1)
    }

    @Test
    fun `the same result delivered twice is applied once`() {
        val id = published()
        val result = result(id, "completed")
        send(result)
        send(result)

        assertThat(consumer.consumeOnce()).isEqualTo(2)

        assertThat(events.stream(JobCompleted::class.java)).hasSize(1)
        assertThat(audited("job.completed", id)).isEqualTo(1)
        assertThat(unacknowledged()).isZero()
        assertThat(redis.opsForStream<String, String>().size(RESULTS_STREAM)).isZero() // deleted once applied
        assertThat(deadLetters()).isEmpty()
    }

    @Test
    fun `a result for another attempt is ignored`() {
        val id = published()
        send(result(id, "completed", attempt = 2))

        assertThat(consumer.consumeOnce()).isEqualTo(1)

        assertThat(row(id)["status"]).isEqualTo("published")
        assertThat(events.stream(JobCompleted::class.java)).isEmpty()
        assertThat(deadLetters()).isEmpty()
    }

    @Test
    fun `a result that arrives while the row is still pending is applied`() {
        val id = dispatched() // the relay died after XADD: the row never became `published`
        send(result(id, "completed"))

        consumer.consumeOnce()

        assertThat(row(id)["status"]).isEqualTo("completed")
        assertThat(relay.relayOnce()).isZero() // not published again
    }

    @Test
    fun `a result claiming another tenant is dead-lettered and changes nothing`() {
        val id = published()
        send(result(id, "completed", tenantId = UUID.randomUUID()))

        assertThat(consumer.consumeOnce()).isEqualTo(1)

        assertThat(row(id)["status"]).isEqualTo("published")
        assertThat(events.stream(JobCompleted::class.java)).isEmpty()
        assertThat(deadLetters().single().value[REASON]).contains("do not match")
        assertThat(unacknowledged()).isZero()
        assertThat(redis.opsForStream<String, String>().size(RESULTS_STREAM)).isZero() // deleted once applied
    }

    @Test
    fun `a result for an unknown job is dead-lettered`() {
        send(result(UUID.randomUUID(), "completed"))

        consumer.consumeOnce()

        assertThat(deadLetters().single().value[REASON]).isEqualTo("unknown job")
    }

    @Test
    fun `malformed results are dead-lettered with the original fields`() {
        val id = published()
        send("not json")
        send(result(id, "failed", error = null)) // failed without error breaks the contract
        send(mapOf("other" to "field"))

        assertThat(consumer.consumeOnce()).isEqualTo(3)

        val dead = deadLetters()
        assertThat(dead).hasSize(3)
        assertThat(dead[0].value[RESULT]).isEqualTo("not json")
        assertThat(dead[1].value[REASON]).contains("job-result.v1")
        assertThat(dead[2].value["other"]).isEqualTo("field")
        assertThat(dead.map { it.value[RedisJobStreams.SOURCE_ID] }).doesNotContainNull()
        assertThat(row(id)["status"]).isEqualTo("published")
        assertThat(unacknowledged()).isZero()
        assertThat(redis.opsForStream<String, String>().size(RESULTS_STREAM)).isZero() // deleted once applied
    }

    @Test
    fun `a result over the size limit is dead-lettered without its payload`() {
        val id = published()
        val limit = JobsProperties.Results().maxResultBytes
        val huge = result(id, "completed").replace("\"repositories\":2", "\"blob\":\"${"x".repeat(limit)}\"")
        send(huge)

        assertThat(consumer.consumeOnce()).isEqualTo(1)

        val dead = deadLetters().single().value
        assertThat(dead[REASON]).isEqualTo("result larger than $limit bytes")
        assertThat(dead).doesNotContainKey(RESULT)
        assertThat(row(id)["status"]).isEqualTo("published")
        assertThat(unacknowledged()).isZero()
    }

    @Test
    fun `the group is created again when Redis lost it`() {
        val id = published()
        redis.delete(RESULTS_STREAM) // group gone, as after a Redis restart without its data

        assertThat(consumer.consumeOnce()).isZero()
        send(result(id, "completed"))
        assertThat(consumer.consumeOnce()).isEqualTo(1)

        assertThat(row(id)["status"]).isEqualTo("completed")
    }
}
