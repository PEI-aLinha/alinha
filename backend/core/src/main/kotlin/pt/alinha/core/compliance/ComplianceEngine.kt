package pt.alinha.core.compliance

import pt.alinha.pluginapi.TenantId

/**
 * Source of truth for compliance status — deterministic, versioned rules, never an LLM (RNF05).
 * Which frameworks apply to an organization is decided by [ApplicabilityRules].
 */
interface ComplianceEngine {
    fun evaluate(tenantId: TenantId, assessmentId: String): Map<String, String>
}
