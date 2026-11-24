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
        // Listed before the lock, so a slow identity provider does not hold it. An assignment committed after this
        // listing is in core.consultant_assignment, which is read under the lock, so the load below stays current.
        val pool = identity.consultants()
        jdbc.sql("set local lock_timeout = '$LOCK_TIMEOUT'").update()
        jdbc.sql("select pg_advisory_xact_lock(?)").param(ASSIGNMENT_LOCK).query { _, _ -> }.list()

        val assignments = assignments()
        assignments.entries.firstOrNull { tenant in it.value.tenants }?.let { (subject, _) ->
            if (pool.none { it.subject == subject && tenant in it.tenants }) {
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
        if (pool.isEmpty()) {
            log.warn("No consultant to assign to organization {}: add one to the consultants group", tenant.value)
            return null
        }

        val chosen = pool.minWith(
            compareBy<Consultant> { (it.tenants + assignments[it.subject]?.tenants.orEmpty()).size }
                .thenBy(nullsFirst()) { assignments[it.subject]?.last }
                .thenBy { it.subject },
        )
        record(tenant, chosen.subject, viaIdentityProvider = false)
        identity.assign(chosen.subject, tenant) // last: the rows above still roll back if it fails
        return AssignedConsultant(chosen.subject, newlyAssigned = true)
    }

    /** A consultant's recorded organizations and when they were last given one. */
    private data class Assignments(val tenants: Set<TenantId>, val last: Instant)

    private fun assignments(): Map<String, Assignments> =
        jdbc.sql("select consultant_subject, tenant_id, assigned_at from core.consultant_assignment")
            .query { rs, _ ->
                Triple(rs.getString("consultant_subject"), TenantId(rs.getObject("tenant_id", UUID::class.java)), rs.getTimestamp("assigned_at").toInstant())
            }
            .list()
            .groupBy({ it.first }) { it.second to it.third }
            .mapValues { (_, rows) -> Assignments(rows.map { it.first }.toSet(), rows.maxOf { it.second }) }

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
