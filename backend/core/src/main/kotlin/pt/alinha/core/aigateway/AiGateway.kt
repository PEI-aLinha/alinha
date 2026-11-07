package pt.alinha.core.aigateway

import java.time.Instant

/**
 * Single entry point to the AI Service (RNF11). Before leaving the core, the
 * context goes through Redaction (RNF13). Returns drafts — never decisions.
 */
interface AiGateway {
    fun draft(task: String, context: Map<String, Any?>): AiDraft
}

/**
 * AI-generated draft. Its provenance is recorded in the Audit Trail (RNF14).
 * Nothing client-facing is published without a consultant's approval (RNF12);
 * in the MVP that approval is the roadmap validation (RoadmapValidated).
 */
data class AiDraft(
    val text: String,
    val model: String,
    val promptVersion: String,
    val sources: List<String>,
    val generatedAt: Instant,
    val aiGenerated: Boolean = true,          // mandatory labelling (AI Act)
    val requiresHumanReview: Boolean = true,
)
