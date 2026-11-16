package pt.alinha.core.tenant.internal

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
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
import org.springframework.web.server.ResponseStatusException
import pt.alinha.core.audit.AuditTrail
import pt.alinha.core.tenant.TenantProvisioning
import pt.alinha.pluginapi.TenantId
import java.util.UUID

internal const val REGISTRANT_AUTHORITY = "ROLE_registrant"
private const val CONSULTANT_AUTHORITY = "ROLE_consultant"
internal const val TENANT_PROVISIONED = "tenant.provisioned"
internal const val ORPHANED_METRIC = "alinha.tenant.provisioning.orphaned"
internal const val ORGANIZATION_ALREADY_EXISTS = "ORGANIZATION_ALREADY_EXISTS"
internal const val IDENTITY_UNAVAILABLE = "IDENTITY_UNAVAILABLE"

/** Creates a tenant for the registering account (ADR 0004). See [TenantProvisioning]. */
@Component
class TenantProvisioner(
    private val tenants: JwtTenantContext,
    private val tx: TransactionTemplate,
    private val jdbc: JdbcClient,
    private val audit: AuditTrail,
    private val directory: IdentityDirectory,
    meters: ObjectProvider<MeterRegistry>,
) : TenantProvisioning {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Accounts left pointing to a rolled-back tenant; alert on any increase (#92 reconciles them). */
    private val orphaned: Counter = Counter.builder(ORPHANED_METRIC)
        .description("Identity assignments that could not be undone after a rollback")
        .register(meters.getIfAvailable { SimpleMeterRegistry() })

    override fun <T> provisionForCurrentUser(block: (TenantId) -> T): T {
        val auth = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
            ?: throw AccessDeniedException("Registering an organization needs an authenticated account")
        requireNoTenant(auth)
        if (auth.authorities.none { it.authority == REGISTRANT_AUTHORITY }) {
            throw AccessDeniedException("Only a new account can register an organization")
        }
        val subject = auth.token.subject
        val tenantId = TenantId(UUID.randomUUID())
        return tenants.runAsNewTenant(tenantId) {
            @Suppress("UNCHECKED_CAST")
            tx.execute { provision(subject, tenantId, block) } as T
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
            throw identityUnavailable(tenantId, e)
        } catch (e: IllegalStateException) {
            throw identityUnavailable(tenantId, e)
        }
    }

    private fun identityUnavailable(tenantId: TenantId, cause: Exception): ResponseStatusException {
        log.error("Identity provider failed while assigning tenant {}", tenantId.value, cause)
        return ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity provider unavailable", cause).apply {
            body.setProperty("code", IDENTITY_UNAVAILABLE)
        }
    }

    /**
     * Undoes the identity assignment only when the transaction is known to have rolled back. With an unknown outcome
     * (e.g. the connection dropped during COMMIT) the tenant may exist, and revoking would lock the account out for
     * good, so it is only reported as possibly orphaned.
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
                    orphaned.increment()
                    log.error("Unknown commit outcome for tenant {}: check its identity assignment", tenantId.value)
                }
            }
        }

        private fun revoke() {
            runCatching { directory.revokeTenant(subject, tenantId) }.onFailure {
                // The account may point to a tenant that does not exist until #92's reconciliation clears it.
                orphaned.increment()
                log.error("Could not undo the identity assignment of rolled-back tenant {}", tenantId.value, it)
            }
        }
    }

    /** Carries `code` in its Problem Details body, so the client can tell it from other 409s. */
    private fun alreadyRegistered() =
        ResponseStatusException(HttpStatus.CONFLICT, "This account already has an organization").apply {
            body.setProperty("code", ORGANIZATION_ALREADY_EXISTS)
        }
}
