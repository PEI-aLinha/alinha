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
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import pt.alinha.core.audit.internal.AuditDatabase
import pt.alinha.core.audit.internal.AuditTestApplication
import pt.alinha.core.tenant.TenantMembership
import pt.alinha.core.tenant.TenantProvisioning
import pt.alinha.pluginapi.TenantId
import java.util.Collections
import java.util.UUID

/** Keeps memberships in memory instead of calling Keycloak; can be told to fail. */
class FakeIdentityMembership : IdentityMembership {
    val accounts: MutableMap<String, DirectoryMember> = Collections.synchronizedMap(mutableMapOf())
    val tenantOf: MutableMap<String, TenantId> = Collections.synchronizedMap(mutableMapOf())
    val joined: MutableList<Pair<String, TenantId>> = Collections.synchronizedList(mutableListOf())
    val left: MutableList<Pair<String, TenantId>> = Collections.synchronizedList(mutableListOf())
    val sessionsEnded: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var failOnJoin = false
    var onLeave: () -> Unit = {}

    fun add(subject: String, tenant: TenantId, role: String, email: String = "$subject@empresa.pt") {
        accounts[subject] = DirectoryMember(subject, email, "Pessoa $subject", setOf(role))
        tenantOf[subject] = tenant
    }

    override fun joinTenant(subject: String, tenantId: TenantId, role: String) {
        joined += subject to tenantId
        if (failOnJoin) error("identity provider down")
        add(subject, tenantId, role)
    }

    override fun leaveTenant(subject: String, tenantId: TenantId, role: String) {
        left += subject to tenantId
        onLeave()
        if (tenantOf[subject] == tenantId) {
            tenantOf.remove(subject)
            accounts.remove(subject)
        }
    }

    override fun members(tenantId: TenantId) = accounts.values.filter { tenantOf[it.subject] == tenantId }

    override fun endSessions(subject: String) {
        sessionsEnded += subject
    }
}

@TestConfiguration
class MembershipTestConfig {
    @Bean fun identityMembership() = FakeIdentityMembership()
    @Bean fun identityDirectory() = FakeIdentityDirectory()
    @Bean fun meterRegistry() = SimpleMeterRegistry()
    // Every test runs from the same mock address: the allowance itself is RegistrationRateLimiterTest's job.
    @Bean fun registrationRateLimiter() = RegistrationRateLimiter(ProvisioningProperties(maxPerClientPerHour = 10_000))
}

@SpringBootTest(classes = [AuditTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MembershipTestConfig::class, TenantMembershipService::class, TenantProvisioner::class)
class TenantMembershipIntegrationTest {
    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) = AuditDatabase.register(registry)
    }

    @Autowired private lateinit var membership: TenantMembership
    @Autowired private lateinit var provisioning: TenantProvisioning
    @Autowired private lateinit var directory: FakeIdentityMembership
    @Autowired private lateinit var jdbc: JdbcTemplate

    private var tenant = TenantId(UUID.randomUUID())
    private lateinit var officer: String

    @BeforeEach
    fun organization() {
        tenant = TenantId(UUID.randomUUID())
        officer = UUID.randomUUID().toString()
        directory.add(officer, tenant, "responsible")
        directory.failOnJoin = false
        directory.onLeave = {}
    }

    @AfterEach
    fun signOut() {
        SecurityContextHolder.clearContext()
        RequestContextHolder.resetRequestAttributes()
    }

    private fun signIn(sub: String, roles: List<String>, claims: Map<String, Any> = emptyMap()) {
        val jwt = Jwt.withTokenValue("t").header("alg", "none").subject(sub)
            .claim("email_verified", true).claims { it.putAll(claims) }.build()
        SecurityContextHolder.getContext().authentication =
            JwtAuthenticationToken(jwt, roles.map { SimpleGrantedAuthority("ROLE_$it") })
    }

    /** As the Designated Officer, in a request resolved to [tenant] (what TenantResolutionInterceptor does). */
    private fun <T> asOfficer(tenant: TenantId = this.tenant, block: () -> T): T {
        signIn(officer, listOf("responsible"), mapOf("tenant_id" to tenant.value.toString()))
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest()))
        RequestContextHolder.getRequestAttributes()!!.setAttribute(TENANT_ATTRIBUTE, tenant, RequestAttributes.SCOPE_REQUEST)
        try {
            return block()
        } finally {
            RequestContextHolder.resetRequestAttributes()
            SecurityContextHolder.clearContext()
        }
    }

    /** A new account, signed in, in a tenant-agnostic request. */
    private fun asNewAccount(email: String, sub: String = UUID.randomUUID().toString(), verified: Boolean = true): String {
        val jwt = Jwt.withTokenValue("t").header("alg", "none").subject(sub)
            .claim("email_verified", verified).claim("email", email).build()
        SecurityContextHolder.getContext().authentication =
            JwtAuthenticationToken(jwt, listOf(SimpleGrantedAuthority("ROLE_registrant")))
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest()))
        return sub
    }

    private fun code(e: ResponseStatusException) = e.body.properties?.get("code")

    private fun audit(action: String, tenant: TenantId = this.tenant) = jdbc.queryForObject(
        "select count(*) from audit.audit_event where tenant_id = ? and action = ?", Long::class.java, tenant.value, action,
    )!!

    @Test
    fun `an invited analyst accepts, joins the organization and the use is audited`() {
        val invitation = asOfficer { membership.invite("  Ana.Silva@Empresa.pt ") }
        assertThat(invitation.email).isEqualTo("ana.silva@empresa.pt")
        assertThat(audit(INVITATION_CREATED)).isEqualTo(1)

        val sub = asNewAccount("ana.silva@empresa.pt")
        val joined = membership.acceptForCurrentUser(invitation.token)

        assertThat(joined).isEqualTo(tenant)
        assertThat(directory.joined).contains(sub to tenant)
        assertThat(audit(INVITATION_ACCEPTED)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select actor from audit.audit_event where action = ? and tenant_id = ?", String::class.java,
            INVITATION_ACCEPTED, tenant.value)).isEqualTo(sub)
        assertThat(asOfficer { membership.pendingInvitations() }).isEmpty()
        assertThat(asOfficer { membership.members() }.map { it.subject to it.role })
            .containsExactly(officer to "responsible", sub to "analyst")
    }

    @Test
    fun `only the hash of the secret is stored`() {
        val invitation = asOfficer { membership.invite("ana@empresa.pt") }

        val stored = jdbc.queryForObject("select token_hash from core.invitation where id = ?", ByteArray::class.java, invitation.id)!!
        assertThat(stored).hasSize(32).isNotEqualTo(invitation.token.toByteArray())
        assertThat(jdbc.queryForObject("select count(*) from core.invitation where encode(token_hash, 'escape') like ?",
            Long::class.java, "%${invitation.token}%")).isZero()
    }

    @Test
    fun `an invitation works once`() {
        val invitation = asOfficer { membership.invite("ana@empresa.pt") }
        asNewAccount("ana@empresa.pt")
        membership.acceptForCurrentUser(invitation.token)

        asNewAccount("ana@empresa.pt")
        val error = assertThrows<ResponseStatusException> { membership.acceptForCurrentUser(invitation.token) }

        assertThat(code(error)).isEqualTo(INVITATION_ALREADY_USED)
    }

    @Test
    fun `an expired invitation is refused and nothing changes`() {
        val invitation = asOfficer { membership.invite("ana@empresa.pt") }
        jdbc.update("update core.invitation set expires_at = now() - interval '1 minute' where id = ?", invitation.id)

        val sub = asNewAccount("ana@empresa.pt")
        val error = assertThrows<ResponseStatusException> { membership.acceptForCurrentUser(invitation.token) }

        assertThat(error.statusCode).isEqualTo(HttpStatus.GONE)
        assertThat(code(error)).isEqualTo(INVITATION_EXPIRED)
        assertThat(directory.joined.map { it.first }).doesNotContain(sub)
        assertThat(asOfficer { membership.pendingInvitations() }.single().expired).isTrue()
    }

    @Test
    fun `an invitation cannot be accepted by another email`() {
        val invitation = asOfficer { membership.invite("ana@empresa.pt") }

        asNewAccount("intruso@outra.pt")
        val error = assertThrows<ResponseStatusException> { membership.acceptForCurrentUser(invitation.token) }

        assertThat(code(error)).isEqualTo(INVITATION_FOR_ANOTHER_EMAIL)
        assertThat(audit(INVITATION_ACCEPTED)).isZero()
    }

    @Test
    fun `another email learns nothing about a used or expired invitation`() {
        val invitation = asOfficer { membership.invite("ana@empresa.pt") }
        jdbc.update("update core.invitation set expires_at = now() - interval '1 minute' where id = ?", invitation.id)

        asNewAccount("intruso@outra.pt")
        assertThat(code(assertThrows<ResponseStatusException> { membership.acceptForCurrentUser(invitation.token) }))
            .isEqualTo(INVITATION_FOR_ANOTHER_EMAIL)
    }

    @Test
    fun `an unverified email cannot accept`() {
        val invitation = asOfficer { membership.invite("ana@empresa.pt") }

        asNewAccount("ana@empresa.pt", verified = false)
        assertThrows<AccessDeniedException> { membership.acceptForCurrentUser(invitation.token) }
    }

    @Test
    fun `an unknown or malformed token is not found`() {
        asNewAccount("ana@empresa.pt")

        assertThat(code(assertThrows<ResponseStatusException> { membership.acceptForCurrentUser("nao-existe") }))
            .isEqualTo(INVITATION_NOT_FOUND)
        assertThat(code(assertThrows<ResponseStatusException> { membership.acceptForCurrentUser("x".repeat(500)) }))
            .isEqualTo(INVITATION_NOT_FOUND)
    }

    @Test
    fun `a revoked invitation is not found`() {
        val invitation = asOfficer { membership.invite("ana@empresa.pt") }
        asOfficer { membership.revokeInvitation(invitation.id) }
        assertThat(audit(INVITATION_REVOKED)).isEqualTo(1)

        asNewAccount("ana@empresa.pt")
        assertThat(code(assertThrows<ResponseStatusException> { membership.acceptForCurrentUser(invitation.token) }))
            .isEqualTo(INVITATION_NOT_FOUND)
    }

    @Test
    fun `inviting the same email again replaces the pending invitation`() {
        val first = asOfficer { membership.invite("ana@empresa.pt") }
        val second = asOfficer { membership.invite("ANA@empresa.pt") }

        assertThat(asOfficer { membership.pendingInvitations() }.map { it.id }).containsExactly(second.id)
        asNewAccount("ana@empresa.pt")
        assertThat(code(assertThrows<ResponseStatusException> { membership.acceptForCurrentUser(first.token) }))
            .isEqualTo(INVITATION_NOT_FOUND)
        assertThat(membership.acceptForCurrentUser(second.token)).isEqualTo(tenant)
    }

    @Test
    fun `another organization cannot see or revoke an invitation`() {
        val invitation = asOfficer { membership.invite("ana@empresa.pt") }
        val other = TenantId(UUID.randomUUID())

        assertThat(asOfficer(other) { membership.pendingInvitations() }).isEmpty()
        val error = assertThrows<ResponseStatusException> { asOfficer(other) { membership.revokeInvitation(invitation.id) } }
        assertThat(code(error)).isEqualTo(INVITATION_NOT_FOUND)
        assertThat(asOfficer { membership.pendingInvitations() }).hasSize(1)
    }

    @Test
    fun `an account that already has an organization cannot accept`() {
        val invitation = asOfficer { membership.invite("ana@empresa.pt") }
        val sub = UUID.randomUUID().toString()
        signIn(sub, listOf("responsible"), mapOf("tenant_id" to UUID.randomUUID().toString(), "email" to "ana@empresa.pt"))

        assertThat(code(assertThrows<ResponseStatusException> { membership.acceptForCurrentUser(invitation.token) }))
            .isEqualTo(ORGANIZATION_ALREADY_EXISTS)
    }

    @Test
    fun `an account that registered an organization a moment ago cannot accept with its stale token`() {
        val invitation = asOfficer { membership.invite("ana@empresa.pt") }
        val sub = UUID.randomUUID().toString()
        jdbc.update("insert into core.tenant (id, owner_subject) values (?, ?)", UUID.randomUUID(), sub)

        asNewAccount("ana@empresa.pt", sub = sub)
        assertThat(code(assertThrows<ResponseStatusException> { membership.acceptForCurrentUser(invitation.token) }))
            .isEqualTo(ORGANIZATION_ALREADY_EXISTS)
    }

    @Test
    fun `an analyst who joined cannot register an organization with the stale token`() {
        val invitation = asOfficer { membership.invite("ana@empresa.pt") }
        val sub = asNewAccount("ana@empresa.pt")
        membership.acceptForCurrentUser(invitation.token)
        RequestContextHolder.resetRequestAttributes()

        val error = assertThrows<ResponseStatusException> { provisioning.provisionForCurrentUser { } }

        assertThat(code(error)).isEqualTo(ORGANIZATION_ALREADY_EXISTS)
        assertThat(jdbc.queryForObject("select count(*) from core.tenant where owner_subject = ?", Long::class.java, sub)).isZero()
    }

    @Test
    fun `an identity provider failure is a 503 and leaves the invitation usable`() {
        val invitation = asOfficer { membership.invite("ana@empresa.pt") }
        directory.failOnJoin = true

        val sub = asNewAccount("ana@empresa.pt")
        val error = assertThrows<ResponseStatusException> { membership.acceptForCurrentUser(invitation.token) }

        assertThat(code(error)).isEqualTo(IDENTITY_UNAVAILABLE)
        assertThat(directory.left).contains(sub to tenant) // the partial join is undone
        assertThat(audit(INVITATION_ACCEPTED)).isZero()
        directory.failOnJoin = false
        asNewAccount("ana@empresa.pt", sub = sub)
        assertThat(membership.acceptForCurrentUser(invitation.token)).isEqualTo(tenant)
    }

    @Test
    fun `an invalid email cannot be invited`() {
        val error = assertThrows<ResponseStatusException> { asOfficer { membership.invite("não é um email") } }

        assertThat(error.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(code(error)).isEqualTo(INVALID_EMAIL)
    }

    @Test
    fun `removing an analyst audits it and ends their sessions`() {
        val analyst = UUID.randomUUID().toString()
        directory.add(analyst, tenant, "analyst")

        asOfficer { membership.removeAnalyst(analyst) }

        assertThat(directory.left).contains(analyst to tenant)
        assertThat(directory.sessionsEnded).contains(analyst)
        assertThat(audit(MEMBER_REMOVED)).isEqualTo(1)
        assertThat(asOfficer { membership.members() }.map { it.subject }).containsExactly(officer)
    }

    @Test
    fun `a removed analyst can be invited again, and can register an organization of their own`() {
        val first = asOfficer { membership.invite("ana@empresa.pt") }
        val sub = asNewAccount("ana@empresa.pt")
        membership.acceptForCurrentUser(first.token)
        asOfficer { membership.removeAnalyst(sub) }

        val again = asOfficer { membership.invite("ana@empresa.pt") }
        asNewAccount("ana@empresa.pt", sub = sub)
        assertThat(membership.acceptForCurrentUser(again.token)).isEqualTo(tenant)

        asOfficer { membership.removeAnalyst(sub) }
        asNewAccount("ana@empresa.pt", sub = sub)
        RequestContextHolder.resetRequestAttributes()
        val own = provisioning.provisionForCurrentUser { it }
        assertThat(jdbc.queryForObject("select owner_subject from core.tenant where id = ?", String::class.java, own.value))
            .isEqualTo(sub)
    }

    @Test
    fun `a removal whose commit fails puts the analyst back`() {
        val analyst = UUID.randomUUID().toString()
        directory.add(analyst, tenant, "analyst")
        directory.onLeave = {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) = error("commit failed")
            })
        }

        assertThrows<IllegalStateException> { asOfficer { membership.removeAnalyst(analyst) } }

        assertThat(asOfficer { membership.members() }.map { it.subject to it.role }).contains(analyst to "analyst")
        assertThat(audit(MEMBER_REMOVED)).isZero()
    }

    @Test
    fun `the Designated Officer and members of other organizations cannot be removed`() {
        val elsewhere = UUID.randomUUID().toString()
        directory.add(elsewhere, TenantId(UUID.randomUUID()), "analyst")

        assertThat(code(assertThrows<ResponseStatusException> { asOfficer { membership.removeAnalyst(officer) } }))
            .isEqualTo(MEMBER_NOT_FOUND)
        assertThat(code(assertThrows<ResponseStatusException> { asOfficer { membership.removeAnalyst(elsewhere) } }))
            .isEqualTo(MEMBER_NOT_FOUND)
        assertThat(directory.left.map { it.first }).doesNotContain(officer, elsewhere)
        assertThat(audit(MEMBER_REMOVED)).isZero()
    }
}
