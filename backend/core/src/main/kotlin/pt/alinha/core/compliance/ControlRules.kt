package pt.alinha.core.compliance

import org.springframework.stereotype.Component

/**
 * The [ComplianceEngine] rules for questionnaire answers (assessment#5, FR10, US15; docs/compliance-engine.md).
 * Deterministic and versioned (RNF05): the same catalogue and answers always give the same [Evaluation] for a
 * [VERSION].
 *
 * - A control is [ControlStatus.COMPLIANT] when every answer is `yes`, a [ControlStatus.GAP] when any answer is
 *   `no` or `unknown` (not confirmed is not in place), and [ControlStatus.PARTIAL] otherwise.
 * - The current level is the highest level whose controls, and those of every level below, are all compliant.
 *   A level without controls is skipped, so it is never reported as reached from nothing. Per domain the same,
 *   over the same levels: a level with no controls in that domain counts as compliant, so the overall level is
 *   the lowest domain level.
 * - Nothing in scope is a catalogue error, never full compliance.
 */
@Component
class ControlRules : ComplianceEngine {

    override fun evaluate(catalogue: Catalogue, maxLevel: ComplianceLevel, answers: Map<String, Answer>): Evaluation {
        val controls = catalogue.controls.filter { it.level <= maxLevel }
        require(controls.isNotEmpty()) {
            "${catalogue.framework} v${catalogue.version} has no controls up to $maxLevel"
        }
        val unanswered = controls.flatMap { it.questions }.map { it.id }.filterNot { it in answers }
        if (unanswered.isNotEmpty()) throw IncompleteAnswersException(unanswered)

        val results = controls.map { control ->
            val (status, reason) = judge(control.questions.map { answers.getValue(it.id) })
            ControlEvaluation(control.id, control.level, status, control.id.substringBefore('.'), reason)
        }
        val levels = ComplianceLevel.entries.filter { level -> level <= maxLevel && results.any { it.level == level } }
        val gaps = results.filter { it.reason != null }.map { result ->
            val requirementIds = catalogue.requirements.filter { result.controlId in it.controls }.map { it.id }
            Gap(result.controlId, requirementIds, result.level, result.status, result.reason!!)
        }
        return Evaluation(
            engineVersion = VERSION,
            currentLevel = currentLevel(results, levels),
            targetLevel = maxLevel,
            coverage = results.count { it.status == ControlStatus.COMPLIANT }.toDouble() / results.size,
            controls = results,
            framework = catalogue.framework,
            catalogueVersion = catalogue.version,
            domains = results.map { it.domain }.distinct().map { domain ->
                DomainEvaluation(domain, currentLevel(results.filter { it.domain == domain }, levels), maxLevel)
            },
            gaps = gaps,
        )
    }

    internal fun status(answers: List<Answer>): ControlStatus = judge(answers).first

    private fun judge(answers: List<Answer>): Pair<ControlStatus, GapReason?> = when {
        answers.isEmpty() -> throw IllegalArgumentException("a control needs at least one answer")
        Answer.NO in answers -> ControlStatus.GAP to GapReason.ANSWERED_NO
        Answer.UNKNOWN in answers -> ControlStatus.GAP to GapReason.ANSWERED_UNKNOWN
        Answer.PARTIAL in answers -> ControlStatus.PARTIAL to GapReason.ANSWERED_PARTIAL
        else -> ControlStatus.COMPLIANT to null
    }

    /** Highest of [levels] such that every result at it and below is compliant; a level with no results passes. */
    private fun currentLevel(results: List<ControlEvaluation>, levels: List<ComplianceLevel>): ComplianceLevel? =
        levels.takeWhile { level -> results.filter { it.level == level }.all { it.status == ControlStatus.COMPLIANT } }
            .lastOrNull()

    companion object {
        const val VERSION = "controls-2026.11-v1"
    }
}
