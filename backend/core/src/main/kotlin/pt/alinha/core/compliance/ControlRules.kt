package pt.alinha.core.compliance

import org.springframework.stereotype.Component

/**
 * The [ComplianceEngine] rules for questionnaire answers (assessment#5, FR10, US15; docs/compliance-engine.md).
 * Deterministic and versioned (RNF05): the same catalogue and answers always give the same [Evaluation] for a
 * [VERSION].
 *
 * - A control is [ControlStatus.COMPLIANT] when every answer is `yes`, a [ControlStatus.GAP] when any answer is
 *   `no` or `unknown` (not confirmed is not in place), and [ControlStatus.PARTIAL] otherwise.
 * - Evidence then applies (#26): failing evidence technically verified or stronger makes the control a gap
 *   ([GapReason.EVIDENCE_FAILED]); a would-be compliant control whose strongest passing evidence is below its level's
 *   bar is partial ([GapReason.WEAK_EVIDENCE]).
 * - Levels are ids of the catalogue's own scale, compared by position (ADR 0007).
 * - The current level is the highest level whose controls, and those of every level below, are all compliant.
 *   A level without controls is skipped, so it is never reported as reached from nothing. Per domain the same,
 *   over the same levels: a level with no controls in that domain counts as compliant, so the overall level is
 *   the lowest domain level.
 * - Nothing in scope is a catalogue error, never full compliance.
 */
@Component
class ControlRules : ComplianceEngine {

    override fun evaluate(
        catalogue: Catalogue,
        maxLevel: String,
        answers: Map<String, Answer>,
        evidence: List<Evidence>,
    ): Evaluation {
        val target = catalogue.levelIndex(maxLevel)
        require(target >= 0) { "Target level '$maxLevel' is not in the scale of ${catalogue.framework}" }
        val controls = catalogue.controls.filter { catalogue.levelIndex(it.level) <= target }
        require(controls.isNotEmpty()) { "${catalogue.framework} v${catalogue.version} has no controls up to $maxLevel" }
        val unanswered = controls.flatMap { it.questions }.map { it.id }.filterNot { it in answers }
        if (unanswered.isNotEmpty()) throw IncompleteAnswersException(unanswered)

        val evidenceByControl = evidence.groupBy { it.controlId }
        val results = controls.map { control ->
            val found = evidenceByControl[control.id].orEmpty()
            // Passing evidence only raises the strength; it never upgrades a no or partial answer.
            val strength = found.filter { it.satisfied }.maxOfOrNull { it.strength } ?: EvidenceStrength.SELF_REPORTED
            val failed = found.any { !it.satisfied && it.strength >= EvidenceStrength.TECHNICALLY_VERIFIED }
            val answered = judge(control.questions.map { answers.getValue(it.id) })
            val (status, reason) = when {
                failed -> ControlStatus.GAP to GapReason.EVIDENCE_FAILED
                answered.first == ControlStatus.COMPLIANT && strength < requiredStrength(control, catalogue) ->
                    ControlStatus.PARTIAL to GapReason.WEAK_EVIDENCE
                else -> answered
            }
            ControlEvaluation(control.id, control.level, status, control.id.substringBefore('.'), reason, strength)
        }
        val levels = catalogue.levels.take(target + 1).map { it.id }
            .filter { level -> results.any { it.level == level } }
        val gaps = results.filter { it.reason != null }.map { result ->
            val requirementIds = catalogue.requirements.filter { result.controlId in it.controls }.map { it.id }
            Gap(result.controlId, requirementIds, result.level, result.status, result.reason!!)
        }
        // A numbered scale starting above 0 (SOC-CMM 1 to 5) has an implicit level 0 with no controls (ADR 0007).
        val belowFirst = catalogue.levels.first().id.toIntOrNull()?.takeIf { it > 0 }?.let { "0" }
        return Evaluation(
            engineVersion = VERSION,
            currentLevel = currentLevel(results, levels) ?: belowFirst,
            targetLevel = maxLevel,
            coverage = results.count { it.status == ControlStatus.COMPLIANT }.toDouble() / results.size,
            controls = results,
            framework = catalogue.framework,
            catalogueVersion = catalogue.version,
            domains = results.map { it.domain }.distinct().map { domain ->
                val level = currentLevel(results.filter { it.domain == domain }, levels) ?: belowFirst
                DomainEvaluation(domain, level, maxLevel)
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

    /** The weakest evidence that lets [control] be met: declared by its level in the catalogue's scale (#26). */
    private fun requiredStrength(control: Control, catalogue: Catalogue): EvidenceStrength {
        val level = catalogue.levels[catalogue.levelIndex(control.level)]
        return if (control.technical) level.requiredStrengthTechnical else level.requiredStrength
    }

    /** Highest of [levels] such that every result at it and below is compliant; a level with no results passes. */
    private fun currentLevel(results: List<ControlEvaluation>, levels: List<String>): String? =
        levels.takeWhile { level -> results.filter { it.level == level }.all { it.status == ControlStatus.COMPLIANT } }
            .lastOrNull()

    companion object {
        const val VERSION = "controls-2026.11-v1"
    }
}
