package pt.alinha.core.compliance

import pt.alinha.pluginapi.TenantId

/** Fonte de verdade do estado de compliance — lógica determinística e versionada, não LLM. */
interface ComplianceEngine {
    fun applicability(tenantId: TenantId): List<String>
    fun evaluate(tenantId: TenantId, assessmentId: String): Map<String, String>
}
