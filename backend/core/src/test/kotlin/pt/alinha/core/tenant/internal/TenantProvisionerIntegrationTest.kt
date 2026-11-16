package pt.alinha.core.tenant.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.server.ResponseStatusException
import pt.alinha.core.audit.internal.AuditDatabase
import pt.alinha.core.audit.internal.AuditTestApplication
import pt.alinha.core.tenant.TenantContext
import pt.alinha.core.tenant.TenantProvisioning
import pt.alinha.pluginapi.TenantId
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Records calls instead of talking to Keycloak; can be told to fail. */
class FakeIdentityDirectory : IdentityDirectory {
    val assigned: MutableList<Pair<String, TenantId>> = Collections.synchronizedList(mutableListOf())
    val revoked: MutableList<Pair<String, TenantId>> = Collections.synchronizedList(mutableListOf())
    var failOnAssign = false
    var failOnRevoke = false

    override fun assignTenant(subject: String, tenantId: TenantId) {
        assigned += subject to tenantId
        if (failOnAssign) error("identity provider down")
    }

    override fun revokeTenant(subject: String, tenantId: TenantId) {
        revoked += subject to tenantId
        if (failOnRevoke) error("identity provider down")
    }
}

@TestConfiguration
class ProvisioningTestConfig {
    @Bean fun identityDirectory() = FakeIdentityDirectory()
    @Bean fun meterRegistry() = SimpleMeterRegistry()
}

// No mock web environment: provisioning also works outside a request, and the test thread stays request-free.
@SpringBootTest(classes = [AuditTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ProvisioningTestConfig::class, TenantProvisioner::class)
class TenantProvisionerIntegrationTest {
    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) = AuditDatabase.register(registry)
    }

    @Autowired private lateinit var provisioning: TenantProvisioning
    @Autowired private lateinit var tenants: TenantContext
    @Autowired private lateinit var directory: FakeIdentityDirectory
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var meters: SimpleMeterRegistry
    @Autowired private lateinit var provisioner: TenantProvisioner

    private fun orphanedCount() = meters.counter(ORPHANED_METRIC).count()

    private lateinit var subject: String

    @BeforeEach
    fun registrant() {
        subject = UUID.randomUUID().toString()
        signIn(subject, roles = listOf("registrant"))
        directory.assigned.clear()
        directory.revoked.clear()
        directory.failOnAssign = false
        directory.failOnRevoke = false
        jdbc.execute("create table if not exists public.provisioning_fixture (id uuid primary key, tenant_id uuid not null)")
    }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    private fun signIn(sub: String, roles: List<String>, claims: Map<String, Any> = emptyMap()) {
        val jwt = Jwt.withTokenValue("t").header("alg", "none").subject(sub).claims { it.putAll(claims) }.build()
        SecurityContextHolder.getContext().authentication =
            JwtAuthenticationToken(jwt, roles.map { SimpleGrantedAuthority("ROLE_$it") })
    }

    private fun tenantRows(tenant: TenantId) =
        jdbc.queryForObject("select count(*) from core.tenant where id = ?", Long::class.java, tenant.value)!!

    private fun auditRows(tenant: TenantId) = jdbc.queryForObject(
        "select count(*) from audit.audit_event where tenant_id = ? and action = 'tenant.provisioned' and actor = ?",
        Long::class.java, tenant.value, subject,
    )!!

    @Test
    fun `creates the tenant, runs the block as that tenant and assigns it to the account`() {
        var seen: TenantId? = null

        val created = provisioning.provisionForCurrentUser { tenant ->
            seen = tenants.current()
            jdbc.update("insert into public.provisioning_fixture values (?, ?)", UUID.randomUUID(), tenant.value)
            tenant
        }

        assertThat(seen).isEqualTo(created)
        assertThat(tenantRows(created)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select owner_subject from core.tenant where id = ?", String::class.java, created.value)).isEqualTo(subject)
        assertThat(auditRows(created)).isEqualTo(1)
        assertThat(directory.assigned).containsExactly(subject to created)
        assertThat(directory.revoked).isEmpty()
    }

    @Test
    fun `the tenant is unbound once provisioning returns`() {
        provisioning.provisionForCurrentUser { }

        assertThrows<IllegalStateException> { tenants.current() }
    }

    @Test
    fun `a second registration by the same account gets 409 and changes nothing`() {
        val first = provisioning.provisionForCurrentUser { it }

        val error = assertThrows<ResponseStatusException> { provisioning.provisionForCurrentUser { it } }

        assertThat(error.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(error.body.properties?.get("code")).isEqualTo(ORGANIZATION_ALREADY_EXISTS)
        assertThat(jdbc.queryForObject("select count(*) from core.tenant where owner_subject = ?", Long::class.java, subject)).isEqualTo(1)
        assertThat(directory.assigned).containsExactly(subject to first)
    }

    @Test
    fun `an account that already has a tenant gets 409`() {
        signIn(subject, listOf("registrant"), mapOf("tenant_id" to UUID.randomUUID().toString()))

        val error = assertThrows<ResponseStatusException> { provisioning.provisionForCurrentUser { } }

        assertThat(error.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(directory.assigned).isEmpty()
    }

    @Test
    fun `a consultant gets 409`() {
        signIn(subject, listOf("consultant", "registrant"))

        assertThrows<ResponseStatusException> { provisioning.provisionForCurrentUser { } }
    }

    @Test
    fun `an account without the registrant role is refused`() {
        signIn(subject, listOf("analyst"))

        assertThrows<AccessDeniedException> { provisioning.provisionForCurrentUser { } }
        assertThat(jdbc.queryForObject("select count(*) from core.tenant where owner_subject = ?", Long::class.java, subject)).isZero()
    }

    @Test
    fun `a failing block rolls back everything and never touches the identity provider`() {
        var tenant: TenantId? = null

        assertThrows<IllegalStateException> {
            provisioning.provisionForCurrentUser {
                tenant = it
                jdbc.update("insert into public.provisioning_fixture values (?, ?)", UUID.randomUUID(), it.value)
                error("invalid organization")
            }
        }

        val created = checkNotNull(tenant)
        assertThat(tenantRows(created)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from public.provisioning_fixture where tenant_id = ?", Long::class.java, created.value)).isZero()
        assertThat(directory.assigned).isEmpty()
        assertThat(directory.revoked).isEmpty()
    }

    @Test
    fun `an identity provider failure is a 503, rolls back and undoes a partial assignment`() {
        directory.failOnAssign = true
        var tenant: TenantId? = null

        val error = assertThrows<ResponseStatusException> { provisioning.provisionForCurrentUser { tenant = it } }

        assertThat(error.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(error.body.properties?.get("code")).isEqualTo(IDENTITY_UNAVAILABLE)
        val created = checkNotNull(tenant)
        assertThat(tenantRows(created)).isZero()
        assertThat(auditRows(created)).isZero()
        assertThat(directory.revoked).containsExactly(subject to created)
    }

    @Test
    fun `a commit failure after the assignment undoes it`() {
        var tenant: TenantId? = null

        assertThrows<IllegalStateException> {
            provisioning.provisionForCurrentUser {
                tenant = it
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun beforeCommit(readOnly: Boolean) = error("commit failed")
                })
            }
        }

        val created = checkNotNull(tenant)
        assertThat(directory.assigned).containsExactly(subject to created)
        assertThat(directory.revoked).containsExactly(subject to created)
        assertThat(tenantRows(created)).isZero()
    }

    @Test
    fun `a failing undo keeps the original error and raises the orphan alert`() {
        directory.failOnAssign = true
        directory.failOnRevoke = true
        val before = orphanedCount()

        val error = assertThrows<ResponseStatusException> { provisioning.provisionForCurrentUser { } }

        assertThat(error.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(directory.revoked).hasSize(1)
        assertThat(orphanedCount()).isEqualTo(before + 1)
    }

    @Test
    fun `registrations above the concurrency limit get 503 and create nothing`() {
        val inside = CountDownLatch(MAX_CONCURRENT_PROVISIONING)
        val release = CountDownLatch(1)
        val auth = SecurityContextHolder.getContext().authentication
        val pool = Executors.newFixedThreadPool(MAX_CONCURRENT_PROVISIONING)
        try {
            val running = List(MAX_CONCURRENT_PROVISIONING) { i ->
                pool.submit<TenantId> {
                    signIn("$subject-$i", listOf("registrant"))
                    try {
                        provisioning.provisionForCurrentUser { inside.countDown(); release.await(); it }
                    } finally {
                        SecurityContextHolder.clearContext()
                    }
                }
            }
            assertThat(inside.await(30, TimeUnit.SECONDS)).isTrue()
            SecurityContextHolder.getContext().authentication = auth

            val error = assertThrows<ResponseStatusException> { provisioning.provisionForCurrentUser { } }

            assertThat(error.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(error.body.properties?.get("code")).isEqualTo(REGISTRATION_BUSY)
            assertThat(jdbc.queryForObject("select count(*) from core.tenant where owner_subject = ?", Long::class.java, subject)).isZero()
            release.countDown()
            running.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `an unknown commit outcome is reported, never revoked`() {
        val tenant = TenantId(UUID.randomUUID())
        val before = orphanedCount()

        provisioner.RevokeOnRollback(subject, tenant).afterCompletion(TransactionSynchronization.STATUS_UNKNOWN)

        assertThat(directory.revoked).isEmpty()
        assertThat(orphanedCount()).isEqualTo(before + 1)
    }

    @Test
    fun `two concurrent registrations by the same account create one tenant`() {
        val auth = SecurityContextHolder.getContext().authentication
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val outcomes = try {
            List(2) {
                pool.submit<Result<TenantId>> {
                    SecurityContextHolder.getContext().authentication = auth
                    start.await()
                    try {
                        runCatching { provisioning.provisionForCurrentUser { tenant -> Thread.sleep(200); tenant } }
                    } finally {
                        SecurityContextHolder.clearContext()
                    }
                }
            }.also { start.countDown() }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
        val conflict = outcomes.single { it.isFailure }.exceptionOrNull()
        assertThat((conflict as ResponseStatusException).statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(jdbc.queryForObject("select count(*) from core.tenant where owner_subject = ?", Long::class.java, subject)).isEqualTo(1)
        assertThat(directory.assigned).hasSize(1)
        assertThat(directory.revoked).isEmpty()
    }

    @Test
    fun `an anonymous caller is refused`() {
        SecurityContextHolder.clearContext()

        assertThrows<AccessDeniedException> { provisioning.provisionForCurrentUser { } }
    }
}
