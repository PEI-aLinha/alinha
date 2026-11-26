package pt.alinha.core.compliance

/**
 * The applicability of an organization as it stands now: its stored scope answers assessed by [ApplicabilityRules],
 * plus the official CNCS qualification when registered ([ApplicabilityResult.officialNis2Classification]).
 * Implemented by the organization plugin; read by the assessment plugin when an assessment starts.
 */
interface ApplicabilityQuery {
    /**
     * For the current tenant ([pt.alinha.core.tenant.TenantContext]; use `runAs` outside a request), so no caller can
     * read another tenant's scope (RNF03). Null when the organization has not answered the scope questions yet.
     */
    fun current(): ApplicabilityResult?
}
