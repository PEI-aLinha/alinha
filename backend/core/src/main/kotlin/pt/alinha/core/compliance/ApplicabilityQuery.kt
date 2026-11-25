package pt.alinha.core.compliance

import pt.alinha.pluginapi.TenantId

/**
 * The applicability of an organization as it stands now: its stored scope answers assessed by [ApplicabilityRules],
 * plus the official CNCS qualification when registered ([ApplicabilityResult.officialNis2Classification]).
 * Implemented by the organization plugin; read by the assessment plugin when an assessment starts.
 */
interface ApplicabilityQuery {
    /**
     * Null when the organization has not answered the scope questions yet. [tenantId] must be the current tenant
     * (request or `TenantContext.runAs`): tenant data is only read in that tenant's session (RNF03).
     */
    fun current(tenantId: TenantId): ApplicabilityResult?
}
