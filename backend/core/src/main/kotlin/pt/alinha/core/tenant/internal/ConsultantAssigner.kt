package pt.alinha.core.tenant.internal

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pt.alinha.core.audit.AuditTrail
import pt.alinha.core.tenant.AssignedConsultant
import pt.alinha.core.tenant.ConsultantAssignment
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.TenantId
import java.time.Instant
import java.util.UUID

/** Audit action of an assignment; details hold the consultant's `sub` only. */
internal const val CONSULTANT_ASSIGNED = "tenant.consultant_assigned"

/** Advisory lock taken for the rest of the transaction, so assignments never choose side by side (any instance). */
private const val ASSIGNMENT_LOCK = 0x616C_696E_6861_0005L

/**
 * How long an assignment waits for the lock before it fails (and is logged by the caller), so a slow identity provider
 * cannot keep a queue of waiting transactions, each holding a pooled connection. The holder's only identity provider
 * call is [ConsultantIdentity.assign], itself bounded by a deadline.
 */
private const val LOCK_TIMEOUT = "15s"

/**
 * [ConsultantAssignment] over the identity provider's consultants ([ConsultantIdentity]) and
 * `core.consultant_assignment`. Raw SQL on purpose: the tie-break reads every tenant's assignment, and the core is the
 * only place allowed to (docs/tenancy.md).
 */
@Component
class ConsultantAssigner(
    private val tenants: TenantContext,
    private val jdbc: JdbcClient,
    private val audit: AuditTrail,
    private val identity: ConsultantIdentity,
) : ConsultantAssignment {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun assignConsultant(): AssignedConsultant? {
        val tenant = tenants.current()
        // Listed before the lock, so a slow identity provider does not hold it. An assignment made while it lists may
        // be missing from it, but it is in core.consultant_assignment, read under the lock, with a later time.
        val listedAt = databaseNow()
        val pool = identity.consultants()
        lockAssignments()

        val recorded = recorded()
        recorded.firstOrNull { it.tenant == tenant }?.let { (_, subject, at) ->
            // A row recorded since the listing (a concurrent call for the same organization) may be missing from it.
            if (at.isBefore(listedAt) && pool.none { it.subject == subject && tenant in it.tenants }) {
                // Removed from the group, disabled, or the organization was taken out of their tenants by hand.
                log.warn("Organization {} is assigned to {}, who no longer has it as an enabled consultant", tenant.value, subject)
            }
            return AssignedConsultant(subject, newlyAssigned = false)
        }

        // Assigned outside this flow (an admin, the demo realm): record it, so the tie-break sees it, and stop there.
        pool.firstOrNull { tenant in it.tenants }?.let { existing ->
            record(tenant, existing.subject, viaIdentityProvider = true)
            return AssignedConsultant(existing.subject, newlyAssigned = false)
        }
        return assignLeastBusy(tenant, pool, recorded, listedAt)
    }

    /**
     * Gives [tenant] to the least busy consultant of [pool]. One who is no longer an enabled consultant (disabled or
     * removed from the group since the listing) is dropped and the next one is tried, so the organization is not left
     * without a consultant while others are free.
     *
     * The load is each consultant's `tenants` in the identity provider, the source of truth (an admin may move
     * organizations there), plus the rows recorded since [listedAt]: those may be missing from the listing. A row is
     * recorded after its identity provider write, so an older one is already in the listing.
     */
    private fun assignLeastBusy(tenant: TenantId, pool: List<Consultant>, recorded: List<Recorded>, listedAt: Instant): AssignedConsultant? {
        val unlisted = recorded.filterNot { it.at.isBefore(listedAt) }.groupBy({ it.consultant }) { it.tenant }
        val last = recorded.groupBy { it.consultant }.mapValues { (_, rows) -> rows.maxOf { it.at } }
        var candidates = pool
        while (candidates.isNotEmpty()) {
            val chosen = candidates.minWith(
                compareBy<Consultant> { (it.tenants + unlisted[it.subject].orEmpty()).size }
                    .thenBy(nullsFirst()) { last[it.subject] }
                    .thenBy { it.subject },
            )
            try {
                identity.assign(chosen.subject, tenant)
            } catch (e: IllegalArgumentException) {
                log.warn("Consultant {} cannot take organization {} ({}), trying the next one", chosen.subject, tenant.value, e.message)
                candidates = candidates - chosen
                continue
            }
            // After the identity provider write: a refused consultant leaves no row, and the row's time is later than
            // the write, which the load above relies on. If this or the commit fails,
            // the organization stays in the consultant's tenants; a retry then takes the "already in the identity
            // provider" branch and records it.
            record(tenant, chosen.subject, viaIdentityProvider = false)
            return AssignedConsultant(chosen.subject, newlyAssigned = true)
        }
        log.warn("No consultant to assign to organization {}: add one to the consultants group", tenant.value)
        return null
    }

    /**
     * Takes [ASSIGNMENT_LOCK]. Only the wait is capped: the caller's later statements in this transaction get back
     * the `lock_timeout` they had before.
     */
    private fun lockAssignments() {
        val previous = jdbc.sql("select current_setting('lock_timeout')").query(String::class.java).single()
        setLockTimeout(LOCK_TIMEOUT)
        jdbc.sql("select pg_advisory_xact_lock(?)").param(ASSIGNMENT_LOCK).query { _, _ -> }.list()
        setLockTimeout(previous)
    }

    /** Like `set local lock_timeout`, which takes no bind parameter. */
    private fun setLockTimeout(value: String) {
        jdbc.sql("select set_config('lock_timeout', ?, true)").param(value).query(String::class.java).single()
    }

    /** One row of `core.consultant_assignment`. */
    private data class Recorded(val tenant: TenantId, val consultant: String, val at: Instant)

    private fun recorded(): List<Recorded> =
        jdbc.sql("select tenant_id, consultant_subject, assigned_at from core.consultant_assignment")
            .query { rs, _ ->
                Recorded(
                    TenantId(rs.getObject("tenant_id", UUID::class.java)),
                    rs.getString("consultant_subject"),
                    rs.getTimestamp("assigned_at").toInstant(),
                )
            }
            .list()

    /** The database's clock, the one `assigned_at` uses. */
    private fun databaseNow(): Instant =
        jdbc.sql("select clock_timestamp()").query { rs, _ -> rs.getTimestamp(1).toInstant() }.single()

    private fun record(tenant: TenantId, consultant: String, viaIdentityProvider: Boolean) {
        // clock_timestamp(), not now(): the time under the lock, so the tie-break order matches the commit order.
        jdbc.sql("insert into core.consultant_assignment (tenant_id, consultant_subject, assigned_at) values (?, ?, clock_timestamp())")
            .params(tenant.value, consultant)
            .update()
        audit.record(
            CONSULTANT_ASSIGNED, "tenant", tenant.value.toString(),
            mapOf("consultant" to consultant, "alreadyInIdentityProvider" to viaIdentityProvider),
        )
    }
}
