package pt.alinha.core.compliance

import pt.alinha.pluginapi.TenantId

/** Source of truth for compliance status — deterministic, versioned rules, never an LLM (RNF05). */
interface ComplianceEngine {
    fun applicability(tenantId: TenantId): List<String>
    fun evaluate(tenantId: TenantId, assessmentId: String): Map<String, String>
}
