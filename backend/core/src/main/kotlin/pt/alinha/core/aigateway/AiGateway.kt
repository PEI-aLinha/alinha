package pt.alinha.core.aigateway

/** Único ponto de acesso ao AI Service. Devolve rascunhos — nunca decisões. */
interface AiGateway {
    fun draft(task: String, context: Map<String, Any?>): AiDraft
}

data class AiDraft(val text: String, val provenance: List<String>, val requiresHumanReview: Boolean = true)
