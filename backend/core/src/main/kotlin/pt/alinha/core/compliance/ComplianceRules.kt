package pt.alinha.core.compliance

import org.springframework.stereotype.Component

/**
 * The Compliance Engine rules (docs/compliance-engine.md). Deterministic and versioned (RNF05): the same input gives
 * the same result for a [VERSION], in catalogue order. Domain = control id up to the first dot ("GR.CO-1-B" -> "GR").
 */
@Component
class ComplianceRules : ComplianceEngine {

    override fun evaluate(input: EvaluationInput): EvaluationResult {
        val catalogue = input.catalogue
        val target = catalogue.levelIndex(input.targetLevel)
        require(target >= 0) { "Target level '${input.targetLevel}' is not in the scale of ${catalogue.framework}" }
        val controls = catalogue.controls.filter { catalogue.levelIndex(it.level) <= target }
            .map { control(it, input.answers, input.evidence.filter { e -> e.controlId == it.id }, catalogue) }
        val met = controls.count { it.status == ControlStatus.MET }
        val gaps = controls.filter { it.status != ControlStatus.MET }.map { result ->
            val requirementIds = input.catalogue.requirements.filter { result.controlId in it.controls }.map { it.id }
            Gap(result.controlId, requirementIds, result.level, result.status, result.reason!!)
        }
        // A numbered scale starting above 0 (SOC-CMM 1 to 5) has an implicit level 0 with no controls (ADR 0007).
        val belowFirst = catalogue.levels.first().id.toIntOrNull()?.takeIf { it > 0 }?.let { "0" }
        val domains = controls.map { it.domain }.distinct().map { domain ->
            val level = currentLevel(controls.filter { it.domain == domain }, catalogue.levels.take(target + 1))
            DomainResult(domain, level ?: belowFirst, input.targetLevel)
        }
        // Lowest domain level ("0" sorts first, at index -1); null (not met) beats any level. No domain gives null.
        val currentLevel = if (domains.any { it.currentLevel == null }) {
            null
        } else {
            domains.map { it.currentLevel!! }.minByOrNull(catalogue::levelIndex)
        }
        return EvaluationResult(VERSION, input.catalogue.framework, input.catalogue.version, input.targetLevel,
            currentLevel, if (controls.isEmpty()) 0.0 else met.toDouble() / controls.size, domains, controls, gaps)
    }

    private fun control(
        control: Control,
        answers: Map<String, Answer>,
        evidence: List<Evidence>,
        catalogue: Catalogue,
    ): ControlResult {
        val given = control.questions.map { answers[it.id] ?: Answer.UNKNOWN }
        // Passing evidence only raises the strength; it never upgrades a no or partial answer.
        val strength = evidence.filter { it.satisfied }.maxOfOrNull { it.strength } ?: EvidenceStrength.SELF_REPORTED
        val failed = evidence.any { !it.satisfied && it.strength >= EvidenceStrength.TECHNICALLY_VERIFIED }
        val (status, reason) = when {
            failed -> ControlStatus.NOT_MET to GapReason.EVIDENCE_FAILED
            Answer.NO in given -> ControlStatus.NOT_MET to GapReason.ANSWERED_NO
            Answer.UNKNOWN in given -> ControlStatus.UNKNOWN to GapReason.NOT_ANSWERED
            Answer.PARTIAL in given -> ControlStatus.PARTIAL to GapReason.ANSWERED_PARTIAL
            strength < requiredStrength(control, catalogue) -> ControlStatus.PARTIAL to GapReason.WEAK_EVIDENCE
            else -> ControlStatus.MET to null
        }
        return ControlResult(control.id, control.id.substringBefore('.'), control.level, status, reason, strength)
    }

    /**
     * The weakest evidence that lets a control be met (#26). QNRCS high and SOC-CMM 4-5 need a verified document, or
     * technical verification for a technical control; every other level accepts the answers (self-reported).
     */
    fun requiredStrength(control: Control, catalogue: Catalogue): EvidenceStrength {
        val verified = when (catalogue.framework) {
            "nis2-qnrcs" -> control.level == "high"
            "soc-cmm" -> (control.level.toIntOrNull() ?: 0) >= 4
            else -> false
        }
        return when {
            !verified -> EvidenceStrength.SELF_REPORTED
            control.technical -> EvidenceStrength.TECHNICALLY_VERIFIED
            else -> EvidenceStrength.DOCUMENT_VERIFIED
        }
    }

    /** Highest of [levels] (the scale up to the target) whose controls, and those of every level below, are all met. */
    private fun currentLevel(controls: List<ControlResult>, levels: List<Level>): String? =
        levels.takeWhile { level -> controls.filter { it.level == level.id }.all { it.status == ControlStatus.MET } }
            .lastOrNull()?.id

    companion object {
        const val VERSION = "compliance-rules/1"
    }
}
