package pt.alinha.core.jobs.internal

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import pt.alinha.core.audit.internal.AuditActor
import pt.alinha.core.audit.internal.AuditDatabase
import pt.alinha.core.audit.internal.AuditEventEntity
import pt.alinha.core.audit.internal.JpaAuditTrail
import pt.alinha.core.jobs.JobDispatcher
import pt.alinha.core.jobs.JobRequest
import pt.alinha.core.tenant.TenantContext
import pt.alinha.core.tenant.internal.HibernateTenantResolver
import pt.alinha.core.tenant.internal.JwtTenantContext
import pt.alinha.pluginapi.TenantId
import java.util.UUID

@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = [AuditEventEntity::class]) // the slice lives in jobs.internal; audit rows are JPA
@Import(
    JwtTenantContext::class,
    HibernateTenantResolver::class,
    AuditActor::class,
    JpaAuditTrail::class,
    JobsConfig::class,
    JobSchemaValidator::class,
    JobOutboxRepository::class,
    OutboxJobDispatcher::class,
    RedisJobStreams::class,
    OutboxRelay::class,
)
class JobsTestApplication

object JobsInfrastructure {
    private const val REDIS_PORT = 6379

    /** One Redis for every jobs test class in the JVM; PostgreSQL is shared with the audit tests. */
    val redis: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:8"))
        .withExposedPorts(REDIS_PORT)
        .apply { start() }

    /** Relay scheduling off: tests drive relayOnce()/notifyOnce() by hand. */
    fun register(registry: DynamicPropertyRegistry, redisPort: Int? = null) {
        AuditDatabase.register(registry)
        registry.add("spring.data.redis.host") { if (redisPort == null) redis.host else "localhost" }
        registry.add("spring.data.redis.port") { redisPort ?: redis.getMappedPort(REDIS_PORT) }
        registry.add("spring.data.redis.timeout") { "500ms" }
        registry.add("alinha.jobs.relay.enabled") { "false" }
    }
}

const val COLLECT_STREAM = "alinha:jobs:evidence.collect:v1"

fun collectRequest(key: String? = null, payload: Map<String, Any?> = validCollectPayload()) = JobRequest(
    type = "evidence.collect",
    payload = payload,
    requestedBy = "code-repository",
    idempotencyKey = key,
)

fun validCollectPayload() = mapOf(
    "provider" to "code-repository",
    "connectionRef" to "code-repository",
    "scope" to mapOf("repositories" to listOf("acme/api")),
)

fun JobDispatcher.dispatchAs(tenants: TenantContext, tx: TransactionTemplate, tenant: TenantId, request: JobRequest): UUID =
    tenants.runAs(tenant) { checkNotNull(tx.execute { dispatch(request) }) }
