package pt.alinha.core.tenant.internal

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
import org.springframework.web.server.ResponseStatusException
import pt.alinha.core.audit.AuditTrail
import pt.alinha.core.tenant.TenantProvisioning
import pt.alinha.pluginapi.TenantId
import java.util.UUID

internal const val REGISTRANT_AUTHORITY = "ROLE_registrant"
private const val CONSULTANT_AUTHORITY = "ROLE_consultant"
internal const val TENANT_PROVISIONED = "tenant.provisioned"

/** Creates a tenant for the registering account (ADR 0004). See [TenantProvisioning]. */
@Component
class TenantProvisioner(
    private val tenants: JwtTenantContext,
    private val tx: TransactionTemplate,
    private val jdbc: JdbcClient,
    private val audit: AuditTrail,
    private val directory: IdentityDirectory,
) : TenantProvisioning {

    private val log = LoggerFactory.getLogger(javaClass)

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
        return ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity provider unavailable", cause)
    }

    private inner class RevokeOnRollback(
        private val subject: String,
        private val tenantId: TenantId,
    ) : TransactionSynchronization {
        override fun afterCompletion(status: Int) {
            if (status == TransactionSynchronization.STATUS_COMMITTED) return
            runCatching { directory.revokeTenant(subject, tenantId) }.onFailure {
                // The account may point to a tenant that does not exist: an admin must clear tenant_id (ADR 0004).
                log.error("Could not undo the identity assignment of rolled-back tenant {}", tenantId.value, it)
            }
        }
    }

    private fun alreadyRegistered() =
        ResponseStatusException(HttpStatus.CONFLICT, "This account already has an organization")
}
