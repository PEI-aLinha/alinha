package pt.alinha.core.tenant.internal

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import pt.alinha.core.audit.AuditTrail
import pt.alinha.core.tenant.IssuedInvitation
import pt.alinha.core.tenant.PendingInvitation
import pt.alinha.core.tenant.TenantMember
import pt.alinha.core.tenant.TenantMembership
import pt.alinha.pluginapi.TenantId
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

internal const val ANALYST_ROLE = "analyst"
internal const val RESPONSIBLE_MEMBER_ROLE = "responsible"
internal const val INVITATION_CREATED = "tenant.invitation-created"
internal const val INVITATION_REVOKED = "tenant.invitation-revoked"
internal const val INVITATION_ACCEPTED = "tenant.invitation-accepted"
internal const val MEMBER_REMOVED = "tenant.member-removed"
internal const val INVITATION_NOT_FOUND = "INVITATION_NOT_FOUND"
internal const val INVITATION_EXPIRED = "INVITATION_EXPIRED"
internal const val INVITATION_ALREADY_USED = "INVITATION_ALREADY_USED"
internal const val INVITATION_FOR_ANOTHER_EMAIL = "INVITATION_FOR_ANOTHER_EMAIL"
internal const val MEMBER_NOT_FOUND = "MEMBER_NOT_FOUND"
internal const val INVALID_EMAIL = "INVALID_EMAIL"
private const val INVITATION_ENTITY = "invitation"
private const val MEMBER_ENTITY = "member"
private const val EMAIL_CLAIM = "email"
private const val EMAIL_VERIFIED = "email_verified"
private const val TOKEN_BYTES = 32
private const val EMAIL_MAX = 254
/** Deliberately loose: the identity provider verifies the address; this only refuses what cannot be one. */
private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/** See [TenantMembership] and ADR 0007. */
@Component
@EnableConfigurationProperties(InvitationProperties::class)
class TenantMembershipService(
    private val tenants: JwtTenantContext,
    private val tx: TransactionTemplate,
    private val jdbc: JdbcClient,
    private val audit: AuditTrail,
    private val directory: IdentityMembership,
    private val properties: InvitationProperties,
) : TenantMembership {

    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    /** Accepting holds a connection while it calls the identity provider, like registering (ADR 0004). */
    private val slots = Semaphore(MAX_CONCURRENT_PROVISIONING)
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun invite(email: String): IssuedInvitation {
        val normalized = email.trim().lowercase()
        if (normalized.length > EMAIL_MAX || !EMAIL.matches(normalized)) {
            throw RegistrationProblem(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid email", INVALID_EMAIL)
        }
        val tenant = tenants.current()
        val invitedBy = currentSubject()
        val token = newToken()
        val now = Instant.now()
        val id = UUID.randomUUID()
        val expiresAt = now.plus(properties.ttl)
        tx.executeWithoutResult {
            jdbc.sql(
                """update core.invitation set revoked_at = ? where tenant_id = ? and email = ?
                   and accepted_at is null and revoked_at is null returning id""",
            ).params(Timestamp.from(now), tenant.value, normalized).query(UUID::class.java).list()
                .forEach { audit.record(INVITATION_REVOKED, INVITATION_ENTITY, it.toString(), mapOf("reason" to "replaced")) }
            jdbc.sql(
                """insert into core.invitation (id, tenant_id, email, role, token_hash, invited_by, created_at, expires_at)
                   values (?, ?, ?, ?, ?, ?, ?, ?)""",
            ).params(id, tenant.value, normalized, ANALYST_ROLE, hash(token), invitedBy, Timestamp.from(now), Timestamp.from(expiresAt))
                .update()
            // No email in the audit trail: personal data stays in the invitation row (docs/audit.md).
            audit.record(INVITATION_CREATED, INVITATION_ENTITY, id.toString(), mapOf("role" to ANALYST_ROLE))
        }
        return IssuedInvitation(id, normalized, token, expiresAt)
    }

    override fun pendingInvitations(): List<PendingInvitation> {
        val now = Instant.now()
        return jdbc.sql(
            """select id, email, created_at, expires_at from core.invitation
               where tenant_id = ? and accepted_at is null and revoked_at is null order by created_at desc""",
        ).param(tenants.current().value).query { rs, _ ->
            val expiresAt = rs.getTimestamp("expires_at").toInstant()
            PendingInvitation(
                rs.getObject("id", UUID::class.java), rs.getString("email"),
                rs.getTimestamp("created_at").toInstant(), expiresAt, expired = !expiresAt.isAfter(now),
            )
        }.list()
    }

    override fun revokeInvitation(invitationId: UUID) {
        val tenant = tenants.current()
        tx.executeWithoutResult {
            val revoked = jdbc.sql(
                """update core.invitation set revoked_at = ? where id = ? and tenant_id = ?
                   and accepted_at is null and revoked_at is null""",
            ).params(Timestamp.from(Instant.now()), invitationId, tenant.value).update()
            if (revoked == 0) throw notFound()
            audit.record(INVITATION_REVOKED, INVITATION_ENTITY, invitationId.toString(), mapOf("reason" to "revoked"))
        }
    }

    override fun acceptForCurrentUser(token: String): TenantId {
        val auth = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
            ?: throw AccessDeniedException("Accepting an invitation needs an authenticated account")
        val hasTenant = auth.token.hasClaim(TENANT_CLAIM) || auth.token.hasClaim(ASSIGNED_TENANTS_CLAIM) ||
            auth.authorities.any { it.authority == "ROLE_consultant" }
        if (hasTenant) throw alreadyInOrganization()
        if (auth.authorities.none { it.authority == REGISTRANT_AUTHORITY }) {
            throw AccessDeniedException("Only a new account can accept an invitation")
        }
        if (auth.token.getClaimAsBoolean(EMAIL_VERIFIED) != true) {
            throw AccessDeniedException("Verify the account's email before accepting an invitation")
        }
        val subject = auth.token.subject?.takeIf { it.isNotBlank() }
            ?: throw AccessDeniedException("The access token has no subject")
        val email = auth.token.getClaimAsString(EMAIL_CLAIM)?.trim()?.lowercase()
            ?: throw AccessDeniedException("The access token has no email")
        // A secret we never issued has no row: refuse it without touching the database's locks.
        val tokenHash = runCatching { hash(token) }.getOrNull() ?: throw notFound()

        if (!inFlight.add(subject)) throw busy()
        try {
            if (!slots.tryAcquire()) throw busy()
            try {
                val invitation = jdbc.sql("select tenant_id from core.invitation where token_hash = ?")
                    .param(tokenHash).query(UUID::class.java).optional().orElseThrow { notFound() }
                val tenantId = TenantId(invitation)
                return tenants.runAsInvitedTenant(tenantId) {
                    tx.execute { accept(subject, email, tokenHash, tenantId) }!!
                }
            } finally {
                slots.release()
            }
        } finally {
            inFlight.remove(subject)
        }
    }

    private fun accept(subject: String, email: String, tokenHash: ByteArray, tenantId: TenantId): TenantId {
        // Same lock as registering an organization, so an account cannot do both at once.
        lockAccount(subject)
        // A stale token (the account registered or joined a moment ago) still says `registrant`: ask the database.
        val owns = jdbc.sql("select exists (select 1 from core.tenant where owner_subject = ?)").param(subject)
            .query(Boolean::class.java).single()
        if (owns || hasAcceptedInvitation(jdbc, subject)) throw alreadyInOrganization()

        val row = jdbc.sql(
            """select id, email, expires_at, accepted_at, revoked_at from core.invitation
               where token_hash = ? and tenant_id = ? for update""",
        ).params(tokenHash, tenantId.value).query { rs, _ ->
            InvitationRow(
                rs.getObject("id", UUID::class.java), rs.getString("email"), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("accepted_at") != null, rs.getTimestamp("revoked_at") != null,
            )
        }.optional().orElseThrow { notFound() }
        val now = Instant.now()
        when {
            row.revoked -> throw notFound()
            row.accepted -> throw RegistrationProblem(HttpStatus.CONFLICT, "Invitation already used", INVITATION_ALREADY_USED)
            !row.expiresAt.isAfter(now) -> throw RegistrationProblem(HttpStatus.GONE, "Invitation expired", INVITATION_EXPIRED)
            row.email != email -> throw RegistrationProblem(
                HttpStatus.FORBIDDEN, "Invitation for another email", INVITATION_FOR_ANOTHER_EMAIL,
            )
        }
        jdbc.sql("update core.invitation set accepted_at = ?, accepted_by = ? where id = ?")
            .params(Timestamp.from(now), subject, row.id).update()
        audit.record(INVITATION_ACCEPTED, INVITATION_ENTITY, row.id.toString(), mapOf("role" to ANALYST_ROLE))

        // Registered before the call: a partial join that throws is undone as well.
        TransactionSynchronizationManager.registerSynchronization(LeaveOnRollback(subject, tenantId))
        identity("joining tenant ${tenantId.value}") { directory.joinTenant(subject, tenantId, ANALYST_ROLE) }
        return tenantId
    }

    override fun members(): List<TenantMember> {
        val tenant = tenants.current()
        val found = identity("listing the members of tenant ${tenant.value}") { directory.members(tenant) }
        return found.mapNotNull { member ->
            val role = when {
                RESPONSIBLE_MEMBER_ROLE in member.roles -> RESPONSIBLE_MEMBER_ROLE
                ANALYST_ROLE in member.roles -> ANALYST_ROLE
                else -> null // e.g. halfway through a join or a removal: not a member yet, or any more
            }
            role?.let { TenantMember(member.subject, member.email, member.name, it) }
        }.sortedWith(compareBy({ it.role != RESPONSIBLE_MEMBER_ROLE }, { it.name ?: it.email ?: "" }))
    }

    override fun removeAnalyst(subject: String) {
        val tenant = tenants.current()
        val member = identity("listing the members of tenant ${tenant.value}") { directory.members(tenant) }
            .firstOrNull { it.subject == subject }
        if (member == null || ANALYST_ROLE !in member.roles || RESPONSIBLE_MEMBER_ROLE in member.roles) {
            throw RegistrationProblem(HttpStatus.NOT_FOUND, "No such analyst", MEMBER_NOT_FOUND)
        }
        tx.executeWithoutResult {
            // Frees the account: it may accept another invitation or register an organization of its own later.
            jdbc.sql("update core.invitation set left_at = ? where tenant_id = ? and accepted_by = ? and left_at is null")
                .params(Timestamp.from(Instant.now()), tenant.value, subject).update()
            audit.record(MEMBER_REMOVED, MEMBER_ENTITY, subject, mapOf("role" to ANALYST_ROLE))
            // Last, so a failure rolls the audit entry back. Registered before the call: if the commit fails after the
            // identity provider let the account go, the analyst is put back, matching the database again.
            TransactionSynchronizationManager.registerSynchronization(RejoinOnRollback(subject, tenant))
            identity("removing a member of tenant ${tenant.value}") { directory.leaveTenant(subject, tenant, ANALYST_ROLE) }
        }
        runCatching { directory.endSessions(subject) }.onFailure {
            // The membership is gone; the next token refresh no longer names the tenant.
            log.warn("Could not end the sessions of a removed member of tenant {}", tenant.value, it)
        }
    }

    private fun lockAccount(subject: String) {
        val locked = jdbc.sql("select pg_try_advisory_lock(hashtextextended(?, 0))")
            .param(ACCOUNT_LOCK_PREFIX + subject).query(Boolean::class.java).single()
        if (!locked) throw busy()
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

            override fun afterCompletion(status: Int) {
                runCatching {
                    jdbc.sql("select pg_advisory_unlock(hashtextextended(?, 0))")
                        .param(ACCOUNT_LOCK_PREFIX + subject).query(Boolean::class.java).single()
                }.onFailure { log.error("Could not release the account lock of an invitation", it) }
            }
        })
    }

    /** Undoes the join only when the transaction is known to have rolled back, as provisioning does (ADR 0004). */
    private inner class LeaveOnRollback(private val subject: String, private val tenantId: TenantId) : TransactionSynchronization {
        override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE - 1

        override fun afterCompletion(status: Int) {
            when (status) {
                TransactionSynchronization.STATUS_COMMITTED -> Unit
                TransactionSynchronization.STATUS_ROLLED_BACK -> runCatching {
                    directory.leaveTenant(subject, tenantId, ANALYST_ROLE)
                }.onFailure {
                    log.error("Could not undo the join of a rolled-back invitation to tenant {}", tenantId.value, it)
                }
                else -> log.error("Unknown commit outcome for an invitation to tenant {}: check the account", tenantId.value)
            }
        }
    }

    /**
     * Undoes a removal whose transaction rolled back (no audit entry, no `left_at`): the account joins again, so it is
     * not left outside the organization in the identity provider while the database still counts it in. With an
     * unknown outcome it is only reported, as provisioning does (ADR 0004).
     */
    private inner class RejoinOnRollback(private val subject: String, private val tenantId: TenantId) : TransactionSynchronization {
        override fun afterCompletion(status: Int) {
            when (status) {
                TransactionSynchronization.STATUS_COMMITTED -> Unit
                TransactionSynchronization.STATUS_ROLLED_BACK -> runCatching {
                    directory.joinTenant(subject, tenantId, ANALYST_ROLE)
                }.onFailure {
                    log.error("Could not undo the removal of a member of tenant {}: check the account", tenantId.value, it)
                }
                else -> log.error("Unknown commit outcome removing a member of tenant {}: check the account", tenantId.value)
            }
        }
    }

    /** Identity provider failures are the platform's problem: 503, details only in the log (as in ADR 0004). */
    private fun <T> identity(what: String, call: () -> T): T =
        try {
            call()
        } catch (e: RuntimeException) {
            log.error("Identity provider failed while {}", what, e)
            throw RegistrationProblem(
                HttpStatus.SERVICE_UNAVAILABLE, "Identity provider unavailable", IDENTITY_UNAVAILABLE, IDENTITY_RETRY_AFTER, e,
            )
        }

    private fun currentSubject(): String =
        (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)?.token?.subject
            ?: throw AccessDeniedException("Inviting needs an authenticated account")

    private fun newToken(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))

    private fun hash(token: String): ByteArray {
        require(token.isNotBlank() && token.length <= TOKEN_BYTES * 2) { "Not an invitation token" }
        return MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    }

    private fun notFound() = RegistrationProblem(HttpStatus.NOT_FOUND, "No such invitation", INVITATION_NOT_FOUND)

    private fun busy() = RegistrationProblem(
        HttpStatus.SERVICE_UNAVAILABLE, "Too many requests at once", REGISTRATION_BUSY, BUSY_RETRY_AFTER,
    )

    private fun alreadyInOrganization() =
        RegistrationProblem(HttpStatus.CONFLICT, "This account already has an organization", ORGANIZATION_ALREADY_EXISTS)

    private data class InvitationRow(
        val id: UUID, val email: String, val expiresAt: Instant, val accepted: Boolean, val revoked: Boolean,
    )
}

/**
 * An account that is in an organization by invitation and was not removed from it: it must not register one of its
 * own nor accept another invitation (ADR 0007).
 */
internal fun hasAcceptedInvitation(jdbc: JdbcClient, subject: String): Boolean =
    jdbc.sql("select exists (select 1 from core.invitation where accepted_by = ? and left_at is null)").param(subject)
        .query(Boolean::class.java).single()
