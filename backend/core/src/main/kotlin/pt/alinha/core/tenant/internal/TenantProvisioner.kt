package pt.alinha.core.tenant.internal

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClientException
import pt.alinha.core.audit.AuditTrail
import pt.alinha.core.tenant.TenantProvisioning
import pt.alinha.pluginapi.TenantId
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

internal const val REGISTRANT_AUTHORITY = "ROLE_registrant"
private const val CONSULTANT_AUTHORITY = "ROLE_consultant"
internal const val TENANT_PROVISIONED = "tenant.provisioned"
internal const val ORPHANED_METRIC = "alinha.tenant.provisioning.orphaned"
internal const val ORPHANED_OUTCOME_TAG = "outcome"
/** The undo failed: the account points to a tenant that was rolled back. Alert on any increase. */
internal const val ORPHANED_REVOKE_FAILED = "revoke_failed"
/** The commit outcome is unknown: the tenant may exist and the account be correct. Check it by hand. */
internal const val ORPHANED_UNKNOWN_COMMIT = "unknown_commit"
internal const val ORGANIZATION_ALREADY_EXISTS = "ORGANIZATION_ALREADY_EXISTS"
internal const val IDENTITY_UNAVAILABLE = "IDENTITY_UNAVAILABLE"
internal const val REGISTRATION_BUSY = "REGISTRATION_BUSY"
private const val EMAIL_VERIFIED_CLAIM = "email_verified"

/**
 * Registrations that may run at once. Each one holds a database connection while it calls the identity provider:
 * up to about 15 s for the assignment, plus about 10 s for the undo when it fails (see [KeycloakIdentityDirectory]).
 * This bounds how much of the pool a slow Keycloak can take from everyone else.
 */
internal const val MAX_CONCURRENT_PROVISIONING = 4

/** `Retry-After` when registrations are busy: about one registration's worst case (see [MAX_CONCURRENT_PROVISIONING]). */
internal val BUSY_RETRY_AFTER: Duration = Duration.ofSeconds(25)

/** `Retry-After` when the identity provider failed: long enough for a restart or a network blip to pass. */
internal val IDENTITY_RETRY_AFTER: Duration = Duration.ofSeconds(60)

/** Creates a tenant for the registering account (ADR 0004). See [TenantProvisioning]. */
@Component
class TenantProvisioner(
    private val tenants: JwtTenantContext,
    private val tx: TransactionTemplate,
    private val jdbc: JdbcClient,
    private val audit: AuditTrail,
    private val directory: IdentityDirectory,
    private val meterRegistry: MeterRegistry,
    private val clients: RegistrationRateLimiter,
) : TenantProvisioning {

    private val log = LoggerFactory.getLogger(javaClass)
    private val slots = Semaphore(MAX_CONCURRENT_PROVISIONING)

    /** Accounts with a registration in flight on this instance: one account cannot take several slots. */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Accounts left pointing to a rolled-back tenant; alert on any increase (#92 reconciles them). */
    private val revokeFailed: Counter = orphanedCounter(ORPHANED_REVOKE_FAILED)

    /** Commits with an unknown outcome: the assignment may be right, so this is a check, not an alert. */
    private val unknownCommit: Counter = orphanedCounter(ORPHANED_UNKNOWN_COMMIT)

    private fun orphanedCounter(outcome: String): Counter = Counter.builder(ORPHANED_METRIC)
        .description("Identity assignments that may point to a tenant that does not exist")
        .tag(ORPHANED_OUTCOME_TAG, outcome)
        .register(meterRegistry)

    override fun <T> provisionForCurrentUser(block: (TenantId) -> T): T {
        val auth = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
            ?: throw AccessDeniedException("Registering an organization needs an authenticated account")
        requireNoTenant(auth)
        if (auth.authorities.none { it.authority == REGISTRANT_AUTHORITY }) {
            throw AccessDeniedException("Only a new account can register an organization")
        }
        // Defence in depth: the realm already blocks sign-in before verification, but a realm change (federated
        // login, admin-created user) must not let an unverified account create an organization.
        if (auth.token.getClaimAsBoolean(EMAIL_VERIFIED_CLAIM) != true) {
            throw AccessDeniedException("Verify the account's email before registering an organization")
        }
        val subject = auth.token.subject
        val tenantId = TenantId(UUID.randomUUID())
        // Checked before the client limit, so a double submit gets 503 `REGISTRATION_BUSY` and costs no attempt.
        if (!inFlight.add(subject)) throw busy()
        try {
            return clients.limit { provisionWithinSlots(subject, tenantId, block) }
        } finally {
            inFlight.remove(subject)
        }
    }

    private fun <T> provisionWithinSlots(subject: String, tenantId: TenantId, block: (TenantId) -> T): T {
        if (!slots.tryAcquire()) throw busy()
        try {
            return tenants.runAsNewTenant(tenantId) {
                @Suppress("UNCHECKED_CAST")
                tx.execute { provision(subject, tenantId, block) } as T
            }
        } finally {
            slots.release()
        }
    }

    private fun requireNoTenant(auth: JwtAuthenticationToken) {
        val hasTenant = auth.token.hasClaim(TENANT_CLAIM) || auth.token.hasClaim(ASSIGNED_TENANTS_CLAIM) ||
            auth.authorities.any { it.authority == CONSULTANT_AUTHORITY }
        if (hasTenant) throw alreadyRegistered()
    }

    private fun <T> provision(subject: String, tenantId: TenantId, block: (TenantId) -> T): T {
        // One tenant per account: a repeated or concurrent registration waits on the unique key, then gets 409.
        val inserted = jdbc.sql("insert into core.tenant (id, owner_subject) values (?, ?) on conflict (owner_subject) do nothing")
            .params(tenantId.value, subject)
            .update()
        if (inserted == 0) throw alreadyRegistered()

        val result = block(tenantId)
        audit.record(TENANT_PROVISIONED, "tenant", tenantId.value.toString())

        // Registered before the call: a partial assignment that throws is undone as well.
        TransactionSynchronizationManager.registerSynchronization(RevokeOnRollback(subject, tenantId))
        assignInDirectory(subject, tenantId) // last: everything else can still roll back
        return result
    }

    /** An identity provider failure is the platform's problem, not the user's: 503, details only in the log. */
    private fun assignInDirectory(subject: String, tenantId: TenantId) {
        try {
            directory.assignTenant(subject, tenantId)
        } catch (e: RestClientException) {
            log.error("Identity provider unreachable or failing while assigning tenant {}", tenantId.value, e)
            throw identityUnavailable(e)
        } catch (e: IllegalStateException) {
            // Not an outage: missing secret, unknown account, missing role or the time budget ran out.
            log.error("Identity assignment refused for tenant {}: {}", tenantId.value, e.message, e)
            throw identityUnavailable(e)
        } catch (e: RuntimeException) {
            // e.g. an unexpected response shape; still a 503 so the client sees one consistent answer.
            log.error("Unexpected failure while assigning tenant {}", tenantId.value, e)
            throw identityUnavailable(e)
        }
    }

    private fun busy() = RegistrationProblem(
        HttpStatus.SERVICE_UNAVAILABLE, "Too many registrations at once", REGISTRATION_BUSY, BUSY_RETRY_AFTER,
    )

    private fun identityUnavailable(cause: Exception) = RegistrationProblem(
        HttpStatus.SERVICE_UNAVAILABLE, "Identity provider unavailable", IDENTITY_UNAVAILABLE, IDENTITY_RETRY_AFTER, cause,
    )

    /**
     * Undoes the identity assignment only when the transaction is known to have rolled back. With an unknown outcome
     * (e.g. the connection dropped during COMMIT) the tenant may exist, and revoking would lock the account out for
     * good, so it is only reported (`outcome=unknown_commit`), apart from the failed undos (`outcome=revoke_failed`).
     */
    internal inner class RevokeOnRollback(
        private val subject: String,
        private val tenantId: TenantId,
    ) : TransactionSynchronization {
        override fun afterCompletion(status: Int) {
            when (status) {
                TransactionSynchronization.STATUS_COMMITTED -> Unit
                TransactionSynchronization.STATUS_ROLLED_BACK -> revoke()
                else -> {
                    unknownCommit.increment()
                    log.error("Unknown commit outcome for tenant {}: check its identity assignment", tenantId.value)
                }
            }
        }

        private fun revoke() {
            runCatching { directory.revokeTenant(subject, tenantId) }.onFailure {
                // The account may point to a tenant that does not exist until #92's reconciliation clears it.
                revokeFailed.increment()
                log.error("Could not undo the identity assignment of rolled-back tenant {}", tenantId.value, it)
            }
        }
    }

    /** Carries `code` in its Problem Details body, so the client can tell it from other 409s. */
    private fun alreadyRegistered() =
        RegistrationProblem(HttpStatus.CONFLICT, "This account already has an organization", ORGANIZATION_ALREADY_EXISTS)
}
