package pt.alinha.core.jobs.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import pt.alinha.core.audit.internal.AuditDatabase
import java.util.UUID

/** The other jobs tests connect as the container superuser; this one proves the grants for alinha_app are enough. */
@SpringBootTest(classes = [JobsTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JobOutboxRuntimeRoleTest {
    companion object {
        /** Throwaway login for the test container, new on every run. */
        private val APP_PASSWORD = UUID.randomUUID().toString()
        private const val TYPE = "evidence.collect"

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) = JobsInfrastructure.register(registry)
    }

    @Autowired private lateinit var jdbc: JdbcTemplate

    private fun newJob(tenant: UUID, key: String) = NewOutboxJob(
        id = UUID.randomUUID(),
        tenantId = tenant,
        type = TYPE,
        version = 1,
        requestedBy = "code-repository",
        envelope = "{}",
        maxAttempts = 3,
        idempotencyKey = key,
    )

    @Test
    fun `runtime role can run every outbox statement`() {
        jdbc.execute("alter role alinha_app login password '$APP_PASSWORD'")
        val dataSource = SingleConnectionDataSource(AuditDatabase.postgres.jdbcUrl, "alinha_app", APP_PASSWORD, true)
        try {
            val repo = JobOutboxRepository(JdbcClient.create(dataSource))
            val tx = TransactionTemplate(DataSourceTransactionManager(dataSource))
            val tenant = UUID.randomUUID()

            val id = checkNotNull(repo.insert(newJob(tenant, "grants")))
            assertThat(repo.insert(newJob(tenant, "grants"))).isNull() // on conflict do nothing
            assertThat(repo.findIdByIdempotencyKey(tenant, TYPE, "code-repository", "grants")).isEqualTo(id)

            // FOR UPDATE needs UPDATE privilege as well as SELECT.
            tx.executeWithoutResult { assertThat(repo.lockDuePending(1_000).map { it.id }).contains(id) }
            repo.markPublished(id)
            repo.markPublishFailed(id, "boom", retryInMillis = 0, dead = true)
            assertThat(repo.findDeadUnnotified(1_000).map { it.id }).contains(id)
            assertThat(repo.markNotified(id)).isTrue()
            repo.deferPublish(id, "redis down", retryInMillis = 0)

            // Results consumer and JobResults.
            tx.executeWithoutResult { assertThat(repo.lockForResult(id)?.id).isEqualTo(id) }
            repo.scheduleRetry(id, "worker: retry", retryInMillis = 0)
            repo.markFailed(id, "{}", "worker: failed")
            repo.markCompleted(id, "{}")
            assertThat(repo.findState(id, tenant)?.status).isEqualTo("completed")
        } finally {
            dataSource.destroy()
        }
    }
}
