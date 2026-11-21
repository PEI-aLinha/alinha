package pt.alinha.core.jobs.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import pt.alinha.core.jobs.JobDispatcher
import pt.alinha.core.jobs.JobError
import pt.alinha.core.jobs.JobResults
import pt.alinha.core.jobs.JobStatus
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.TenantId
import java.util.UUID

@SpringBootTest(classes = [JobsTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JobResultsTest {
    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) = JobsInfrastructure.register(registry)
    }

    @Autowired private lateinit var dispatcher: JobDispatcher
    @Autowired private lateinit var results: JobResults
    @Autowired private lateinit var outbox: JobOutboxRepository
    @Autowired private lateinit var tenants: TenantContext
    @Autowired private lateinit var tx: TransactionTemplate
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val tenant = TenantId(UUID.randomUUID())

    @BeforeEach
    fun clean() {
        jdbc.update("delete from core.job_outbox")
    }

    private fun dispatch() = dispatcher.dispatchAs(tenants, tx, tenant, collectRequest())

    private fun find(id: UUID, as_: TenantId = tenant) = tenants.runAs(as_) { results.find(id) }

    @Test
    fun `a job that has not finished has a status and nothing else`() {
        val id = dispatch()

        val job = checkNotNull(find(id))

        assertThat(job.status).isEqualTo(JobStatus.PENDING)
        assertThat(job.type).isEqualTo("evidence.collect")
        assertThat(job.attempt).isEqualTo(1)
        assertThat(job.result).isNull()
        assertThat(job.error).isNull()
        assertThat(job.finishedAt).isNull()
    }

    @Test
    fun `a completed job exposes the worker's result`() {
        val id = dispatch()
        outbox.markCompleted(id, """{"status":"completed","result":{"repositories":2}}""")

        val job = checkNotNull(find(id))

        assertThat(job.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(job.result).isEqualTo(mapOf("repositories" to 2))
        assertThat(job.error).isNull()
        assertThat(job.finishedAt).isNotNull()
    }

    @Test
    fun `a failed job exposes the worker's error`() {
        val id = dispatch()
        outbox.markFailed(id, """{"status":"failed","error":{"code":"auth","message":"token expired"}}""", "auth: token expired")

        val job = checkNotNull(find(id))

        assertThat(job.status).isEqualTo(JobStatus.FAILED)
        assertThat(job.result).isNull()
        assertThat(job.error).isEqualTo(JobError("auth", "token expired"))
    }

    @Test
    fun `a dead job reports publish_failed without the internal error`() {
        val id = dispatch()
        outbox.markPublishFailed(id, "IllegalStateException: internal detail", retryInMillis = 0, dead = true)

        val job = checkNotNull(find(id))

        assertThat(job.status).isEqualTo(JobStatus.DEAD)
        assertThat(job.error).isEqualTo(JobError.PUBLISH_FAILED_ERROR)
    }

    @Test
    fun `another tenant's job reads as missing`() {
        val id = dispatch()

        assertThat(find(id, TenantId(UUID.randomUUID()))).isNull()
        assertThat(find(UUID.randomUUID())).isNull()
    }
}
