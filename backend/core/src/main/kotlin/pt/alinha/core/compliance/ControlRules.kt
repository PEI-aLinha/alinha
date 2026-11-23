package pt.alinha.core.compliance

import org.springframework.stereotype.Component

/**
 * The [ComplianceEngine] rules for questionnaire answers (assessment#5, FR10, US15). Deterministic and versioned
 * (RNF05): the same catalogue and answers always give the same [Evaluation] for a [VERSION].
 *
 * - A control is [ControlStatus.COMPLIANT] when every answer is `yes`, a [ControlStatus.GAP] when any answer is
 *   `no` or `unknown` (not confirmed is not in place), and [ControlStatus.PARTIAL] otherwise.
 * - The current level is the highest level whose controls, and those of every level below, are all compliant.
 */
@Component
class ControlRules : ComplianceEngine {

    override fun evaluate(catalogue: Catalogue, maxLevel: ComplianceLevel, answers: Map<String, Answer>): Evaluation {
        val controls = catalogue.controls.filter { it.level <= maxLevel }
        val unanswered = controls.flatMap { it.questions }.map { it.id }.filterNot { it in answers }
        if (unanswered.isNotEmpty()) throw IncompleteAnswersException(unanswered)

        val results = controls.map { control ->
            ControlEvaluation(control.id, control.level, status(control.questions.map { answers.getValue(it.id) }))
        }
        return Evaluation(
            engineVersion = VERSION,
            currentLevel = currentLevel(results, maxLevel),
            targetLevel = maxLevel,
            coverage = if (results.isEmpty()) 0.0 else results.count { it.status == ControlStatus.COMPLIANT }.toDouble() / results.size,
            controls = results,
        )
    }

    fun status(answers: List<Answer>): ControlStatus = when {
        answers.any { it == Answer.NO || it == Answer.UNKNOWN } -> ControlStatus.GAP
        answers.any { it == Answer.PARTIAL } -> ControlStatus.PARTIAL
        else -> ControlStatus.COMPLIANT
    }

    private fun currentLevel(results: List<ControlEvaluation>, maxLevel: ComplianceLevel): ComplianceLevel? =
        ComplianceLevel.entries
            .filter { it <= maxLevel }
            .takeWhile { level -> results.filter { it.level == level }.all { it.status == ControlStatus.COMPLIANT } }
            .lastOrNull()

    companion object {
        const val VERSION = "controls-2026.11-v1"
    }
}
