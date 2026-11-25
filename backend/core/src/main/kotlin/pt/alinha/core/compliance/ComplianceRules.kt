package pt.alinha.core.compliance

import org.springframework.stereotype.Component

/**
 * The Compliance Engine rules (docs/compliance-engine.md). Deterministic and versioned (RNF05): the same input gives
 * the same result for a [VERSION], in catalogue order. Domain = control id up to the first dot ("GR.CO-1-B" -> "GR").
 */
@Component
class ComplianceRules : ComplianceEngine {

    override fun evaluate(input: EvaluationInput): EvaluationResult {
        val controls = input.catalogue.controls.filter { it.level <= input.targetLevel }
            .map { control(it, input.answers) }
        val met = controls.count { it.status == ControlStatus.MET }
        val gaps = controls.filter { it.status != ControlStatus.MET }.map { result ->
            val requirementIds = input.catalogue.requirements.filter { result.controlId in it.controls }.map { it.id }
            Gap(result.controlId, requirementIds, result.level, result.status, result.reason!!)
        }
        val domains = controls.map { it.domain }.distinct().map { domain ->
            val level = currentLevel(controls.filter { it.domain == domain }, input.targetLevel)
            DomainResult(domain, level, input.targetLevel)
        }
        // Lowest domain level; null (not met) beats any level. No domain evaluated gives null too.
        val currentLevel =
            if (domains.any { it.currentLevel == null }) null else domains.minOfOrNull { it.currentLevel!! }
        return EvaluationResult(VERSION, input.catalogue.framework, input.catalogue.version, input.targetLevel,
            currentLevel, if (controls.isEmpty()) 0.0 else met.toDouble() / controls.size, domains, controls, gaps)
    }

    private fun control(control: Control, answers: Map<String, Answer>): ControlResult {
        val given = control.questions.map { answers[it.id] ?: Answer.UNKNOWN }
        val (status, reason) = when {
            Answer.NO in given -> ControlStatus.NOT_MET to GapReason.ANSWERED_NO
            Answer.UNKNOWN in given -> ControlStatus.UNKNOWN to GapReason.NOT_ANSWERED
            Answer.PARTIAL in given -> ControlStatus.PARTIAL to GapReason.ANSWERED_PARTIAL
            else -> ControlStatus.MET to null
        }
        return ControlResult(control.id, control.id.substringBefore('.'), control.level, status, reason)
    }

    /** Highest level up to [target] whose controls, and those of every level below, are all met. */
    private fun currentLevel(controls: List<ControlResult>, target: ComplianceLevel): ComplianceLevel? =
        ComplianceLevel.entries.filter { it <= target }
            .takeWhile { level -> controls.filter { it.level == level }.all { it.status == ControlStatus.MET } }
            .lastOrNull()

    companion object {
        const val VERSION = "compliance-rules/1"
    }
}
