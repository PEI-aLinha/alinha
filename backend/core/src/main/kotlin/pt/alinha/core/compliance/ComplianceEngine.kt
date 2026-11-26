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
     * Evaluates the controls of [catalogue] up to [maxLevel], an id of the catalogue's level scale (ADR 0007), from
     * [answers] (question id -> answer).
     * Throws [IncompleteAnswersException] when a question of those controls has no answer.
     */
    fun evaluate(catalogue: Catalogue, maxLevel: String, answers: Map<String, Answer>): Evaluation
}

/** Implemented by the assessment plugin (assessment#5); read by the AI Gateway context builder. */
interface EvaluationResults {
    /**
     * The latest stored evaluation of [framework] for the current tenant ([pt.alinha.core.tenant.TenantContext];
     * use `runAs` outside a request), or null when there is none.
     */
    fun latest(framework: String): Evaluation?
}

/**
 * What the engine computed, with the [engineVersion] of the rules, so the result can always be explained.
 * Levels are ids of the catalogue's level scale (ADR 0007).
 */
data class Evaluation(
    val engineVersion: String,
    /** Highest level whose controls, and those of every level below, are all [ControlStatus.COMPLIANT]. */
    val currentLevel: String?,
    val targetLevel: String,
    /** Share of the evaluated controls that are [ControlStatus.COMPLIANT], 0..1. */
    val coverage: Double,
    /** Every evaluated control, in catalogue order. */
    val controls: List<ControlEvaluation>,
    val framework: String,
    val catalogueVersion: Int,
    /** Current level per domain, in catalogue order of first appearance. [currentLevel] is the lowest of them. */
    val domains: List<DomainEvaluation>,
    /** Every control that is not compliant, in catalogue order. */
    val gaps: List<Gap>,
)

data class ControlEvaluation(
    val controlId: String,
    val level: String,
    val status: ControlStatus,
    /** Control id up to the first dot ("GR.CO-1-B" -> "GR"). */
    val domain: String,
    /** Null only when [status] is [ControlStatus.COMPLIANT]. */
    val reason: GapReason?,
)

data class DomainEvaluation(
    val domain: String,
    /**
     * Highest level fully compliant in this domain. When not even the first level with controls is: the scale's lowest
     * level if it has no controls (SOC-CMM "0"), else null (QNRCS). Which controls fall short (not compliant,
     * unknown or partial) is in [Evaluation.controls]: the screen uses it to show "por avaliar".
     */
    val currentLevel: String?,
    val targetLevel: String,
)

/** A control that is not compliant, with the catalogue requirements that point to it (in catalogue order). */
data class Gap(
    val controlId: String,
    val requirementIds: List<String>,
    val level: String,
    val status: ControlStatus,
    val reason: GapReason,
)

/** Why a control is a gap. */
enum class GapReason { ANSWERED_NO, ANSWERED_UNKNOWN, ANSWERED_PARTIAL }

/** `partial` and `gap` both count as gaps for the roadmap. */
enum class ControlStatus {
    @JsonProperty("compliant") COMPLIANT,
    @JsonProperty("partial") PARTIAL,
    @JsonProperty("gap") GAP,
}

class IncompleteAnswersException(val unanswered: List<String>) :
    RuntimeException("${unanswered.size} question(s) without an answer")
