package pt.alinha.core.compliance

import pt.alinha.pluginapi.TenantId

/**
 * Source of truth for control status: deterministic, versioned rules, never an LLM (RNF05).
 * Turns a catalogue, a target level and the answers into control statuses, coverage, gaps and a current level per
 * domain (docs/compliance-engine.md). Writes nothing: callers store the result, audit and check permissions.
 * Which frameworks apply to an organization is decided by [ApplicabilityRules].
 */
interface ComplianceEngine {
    fun evaluate(input: EvaluationInput): EvaluationResult
}

/** Implemented by the assessment plugin (assessment#5); read by the AI Gateway context builder. */
interface EvaluationResults {
    /** The latest stored evaluation of a framework for a tenant, or null when there is none. */
    fun latest(tenantId: TenantId, framework: String): EvaluationResult?
}
