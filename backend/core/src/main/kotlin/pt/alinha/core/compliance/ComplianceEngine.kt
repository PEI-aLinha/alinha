package pt.alinha.core.compliance

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Source of truth for compliance status — deterministic, versioned rules, never an LLM (RNF05).
 * Which frameworks apply to an organization is decided by [ApplicabilityRules].
 *
 * The core cannot read a plugin's data, so the caller (the assessment plugin) passes the catalogue version the
 * assessment was started with, its highest level and the answers.
 */
interface ComplianceEngine {

    /**
     * Evaluates the controls of [catalogue] up to [maxLevel] from [answers] (question id -> answer).
     * Throws [IncompleteAnswersException] when a question of those controls has no answer.
     */
    fun evaluate(catalogue: Catalogue, maxLevel: ComplianceLevel, answers: Map<String, Answer>): Evaluation
}

/** What the engine computed, with the [engineVersion] of the rules, so the result can always be explained. */
data class Evaluation(
    val engineVersion: String,
    /** Highest level whose controls, and those of every level below, are all [ControlStatus.COMPLIANT]. */
    val currentLevel: ComplianceLevel?,
    val targetLevel: ComplianceLevel,
    /** Share of the evaluated controls that are [ControlStatus.COMPLIANT], 0..1. */
    val coverage: Double,
    /** Every evaluated control, in catalogue order. */
    val controls: List<ControlEvaluation>,
)

data class ControlEvaluation(
    val controlId: String,
    val level: ComplianceLevel,
    val status: ControlStatus,
)

/** `partial` and `gap` both count as gaps for the roadmap. */
enum class ControlStatus {
    @JsonProperty("compliant") COMPLIANT,
    @JsonProperty("partial") PARTIAL,
    @JsonProperty("gap") GAP,
}

class IncompleteAnswersException(val unanswered: List<String>) :
    RuntimeException("${unanswered.size} question(s) without an answer")
