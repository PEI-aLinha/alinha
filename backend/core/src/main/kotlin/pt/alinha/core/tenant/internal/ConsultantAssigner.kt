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

/** Audit action of an assignment; details hold the consultant's `sub` only. */
internal const val CONSULTANT_ASSIGNED = "tenant.consultant_assigned"

/** Advisory lock taken for the whole transaction, so assignments never run side by side (any instance). */
private const val ASSIGNMENT_LOCK = 0x616C_696E_6861_0005L

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
        jdbc.sql("select pg_advisory_xact_lock(?)").param(ASSIGNMENT_LOCK).query { _, _ -> }.list()

        recorded(tenant)?.let { return AssignedConsultant(it, newlyAssigned = false) }

        val pool = identity.consultants()
        // Assigned outside this flow (an admin, the demo realm): record it, so the tie-break sees it, and stop there.
        pool.firstOrNull { tenant in it.tenants }?.let { existing ->
            record(tenant, existing.subject, viaIdentityProvider = true)
            return AssignedConsultant(existing.subject, newlyAssigned = false)
        }
        if (pool.isEmpty()) {
            log.warn("No consultant to assign to organization {}: add one to the consultants group", tenant.value)
            return null
        }

        val lastAssigned = lastAssignments()
        val chosen = pool.minWith(
            compareBy<Consultant> { it.tenants.size }
                .thenBy(nullsFirst()) { lastAssigned[it.subject] }
                .thenBy { it.subject },
        )
        record(tenant, chosen.subject, viaIdentityProvider = false)
        identity.assign(chosen.subject, tenant) // last: the rows above still roll back if it fails
        return AssignedConsultant(chosen.subject, newlyAssigned = true)
    }

    private fun recorded(tenant: TenantId): String? =
        jdbc.sql("select consultant_subject from core.consultant_assignment where tenant_id = ?")
            .param(tenant.value)
            .query(String::class.java)
            .optional()
            .orElse(null)

    private fun lastAssignments(): Map<String, Instant> =
        jdbc.sql("select consultant_subject, max(assigned_at) as last from core.consultant_assignment group by consultant_subject")
            .query { rs, _ -> rs.getString("consultant_subject") to rs.getTimestamp("last").toInstant() }
            .list()
            .toMap()

    private fun record(tenant: TenantId, consultant: String, viaIdentityProvider: Boolean) {
        jdbc.sql("insert into core.consultant_assignment (tenant_id, consultant_subject) values (?, ?)")
            .params(tenant.value, consultant)
            .update()
        audit.record(
            CONSULTANT_ASSIGNED, "tenant", tenant.value.toString(),
            mapOf("consultant" to consultant, "alreadyInIdentityProvider" to viaIdentityProvider),
        )
    }
}
